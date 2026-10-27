package com.tyler.primelink.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import com.tyler.primelink.R
import com.tyler.primelink.actions.Actions
import com.tyler.primelink.actions.Policy
import com.tyler.primelink.actions.RpcError
import com.tyler.primelink.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service: LAN listener (TCP, port = PORT) + mDNS advert _primelink._tcp. Home Wi-Fi only (no relay, no cloud).
 * Idle cost is one blocked accept() and the NSD advert; no polling, no wakelock.
 */
class LinkService : Service() {
    companion object {
        const val PORT = 48800 // example default; must match the PC companion (TETHER_PORT)
        const val TAG = "PrimeLink"
        private const val CH = "plink_bg"; private const val CH_ALERT = "plink_session"
        const val ACTION_DISCONNECT = "com.tyler.primelink.DISCONNECT"
        fun start(c: Context) { c.startForegroundService(Intent(c, LinkService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, LinkService::class.java)) }
        fun lanAddress(c: Context): String? {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }

    private lateinit var store: LinkStore
    private lateinit var audit: Audit
    private lateinit var actions: Actions
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = AtomicInteger(0)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var failedPairs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = LinkStore(this); audit = Audit(this); actions = Actions(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Tether background (can turn off)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Required while Tether listens on Wi-Fi."; setShowBadge(false); setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Tether session alerts", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown when Prime starts using your phone after 5 minutes idle."; setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(11, bgNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (server == null) startServer()
        return START_STICKY
    }

    private fun pi(i: Intent, code: Int) = PendingIntent.getActivity(this, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    private fun disconnectPi() = PendingIntent.getBroadcast(this, 12, Intent(this, DisconnectReceiver::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)

    private fun bgNotification(): Notification = Notification.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_stat_link).setContentTitle("> Tether").setContentText("listening for your PC on Wi-Fi")
        .setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_SECRET)
        .setOnlyAlertOnce(true).setContentIntent(pi(Intent(this, MainActivity::class.java), 11))
        .addAction(Notification.Action.Builder(null, "Disconnect", disconnectPi()).build()).build()

    private fun sessionAlert(pc: String, tool: String) {
        val n = Notification.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Prime is using your phone").setContentText("$pc · $tool · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}")
            .setAutoCancel(true).setContentIntent(pi(Intent(this, MainActivity::class.java), 13))
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnectPi()).build()).build()
        getSystemService(NotificationManager::class.java).notify(14, n)
    }

    private fun startServer() {
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) } }
            .getOrElse { Log.w(TAG, "bind failed", it); stopSelf(); return }
        server = ss; LinkState.running.value = true; LinkState.address.value = lanAddress(this)?.let { "$it:$PORT" }
        registerNsd()
        scope.launch {
            while (isActive && !ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                if (conns.get() >= 6) { s.close(); continue }
                launch { handle(s) }
            }
        }
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply { serviceName = "PrimeLink-" + store.phoneId.take(6); serviceType = "_primelink._tcp"; port = PORT
            setAttribute("id", store.phoneId); setAttribute("v", "1") }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "nsd failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd = getSystemService(NsdManager::class.java)
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    private fun handle(s: Socket) {
        conns.incrementAndGet(); sockets += s
        var pcId: String? = null
        try {
            s.tcpNoDelay = true; s.keepAlive = true; s.soTimeout = 15_000
            val io = FrameIO(s.getInputStream(), s.getOutputStream())
            val first = io.read()
            val t = runCatching { JSONObject(String(first)).optString("t") }.getOrDefault("")
            if (t == "pair1") { pair(io, first, s); return }
            val (ch, id) = PhoneProtocol.sessionResponder(io, first, store.phoneId, store.secretKey(), { store.pubFor(it) })
            pcId = id; store.touch(id); s.soTimeout = 0
            LinkState.sessionOpened(id)
            val pcName = store.nameFor(id)
            val gate = CommandGate({ store.cmdKey() })
            while (true) {
                val req = ch.recv()
                if (store.pubFor(id) == null) { ch.send(JSONObject().put("id", req.opt("id")).put("e", err("revoked", "PC was unlinked on the phone"))); break }
                when (val g = gate.check(req)) {
                    is CommandGate.Result.Bad -> {
                        audit.add(pcName, req.optString("m").take(40), "", g.code); LinkState.auditTick.value++
                        ch.send(JSONObject().put("id", g.id).put("e", err(g.code, g.msg)))
                        if (g.code == "auth_fail") break
                    }
                    is CommandGate.Result.Ok -> scope.launch { run(ch, pcName, g) }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "connection ended (${e.javaClass.simpleName})")
        } finally {
            pcId?.let { LinkState.sessionClosed(it) }
            runCatching { s.close() }; sockets -= s; conns.decrementAndGet()
        }
    }

    private fun err(code: String, msg: String?) = JSONObject().put("code", code).put("msg", msg ?: code)

    private fun run(ch: SecureChannel, pcName: String, g: CommandGate.Result.Ok) {
        val now = System.currentTimeMillis()
        if (LinkState.shouldAlert(now, LinkState.lastCommandAt)) runCatching { sessionAlert(pcName, g.method) }
        LinkState.lastCommandAt = now
        val resp = JSONObject().put("id", g.id)
        val outcome = try {
            Policy.gate(g.method, actions.isLocked())
            resp.put("r", actions.call(g.method, g.params)); "ok"
        } catch (e: RpcError) { resp.put("e", err(e.code, e.message)); e.code }
        catch (e: SecurityException) { resp.put("e", err("permission", e.message)); "permission" }
        catch (e: Exception) { Log.w(TAG, "tool ${g.method} failed", e); resp.put("e", err("error", e.message ?: e.javaClass.simpleName)); "error" }
        val extra = if (g.method == "read_notifications") resp.optJSONObject("r")?.let { " count=${it.optInt("count")}" } ?: "" else ""
        audit.add(pcName, g.method, Policy.auditArgs(g.method, g.params) + extra, outcome); LinkState.auditTick.value++
        runCatching { ch.send(resp) }
    }

    private fun pair(io: FrameIO, first: ByteArray, s: Socket) {
        if (!LinkState.pairingOpen()) { io.write(json("t" to "err", "msg" to "phone is not in pairing mode (open Tether > PAIR NEW PC)").bytes()); return }
        s.soTimeout = 150_000
        val fut = CompletableFuture<Boolean>(); LinkState.decision = fut
        try {
            val pc = PhoneProtocol.pairResponder(io, first, store.phoneId, android.os.Build.MODEL, store.secretKey(), store.token(),
                showCode = { code, name -> LinkState.pairing.value = LinkState.pairing.value?.copy(code = code, pcName = name) },
                allow = { _ ->
                    LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = true)
                    runCatching { fut.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
                })
            store.addPc(pc.pcId, pc.name, pc.pub)
            audit.add(pc.name, "pair", "", "paired"); LinkState.auditTick.value++
            LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = false, result = "paired with ${pc.name}")
            failedPairs = 0
        } catch (e: Exception) {
            Log.w(TAG, "pairing failed: ${e.message}")
            if (++failedPairs >= 3) { LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = false, result = "pairing closed after 3 failures"); failedPairs = 0 }
            else LinkState.pairing.value = LinkState.pairing.value?.copy(code = null, awaitingAllow = false, result = "pairing failed: ${e.message}")
        } finally { LinkState.decision = null }
    }

    override fun onDestroy() {
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        runCatching { server?.close() }; sockets.forEach { runCatching { it.close() } }
        scope.cancel(); server = null
        LinkState.running.value = false; LinkState.sessions.value = emptyMap()
        super.onDestroy()
    }
}

/** Notification / tile "Disconnect": turns the link off and drops every session. */
class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        LinkStore(c).enabled = false; LinkService.stop(c)
        c.getSystemService(NotificationManager::class.java).cancel(14)
    }
}

/** Restarts the link after reboot / app update if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) && LinkStore(c).enabled)
            runCatching { LinkService.start(c) }
    }
}
