package com.tyler.primelink.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.tyler.primelink.BuildConfig
import com.tyler.primelink.actions.Actions
import com.tyler.primelink.link.Audit
import com.tyler.primelink.link.LinkService
import com.tyler.primelink.link.LinkState
import com.tyler.primelink.link.LinkStore
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LinkStore(this); val audit = Audit(this); val actions = Actions(this)
        if (store.enabled) LinkService.start(this)
        setContent {
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) { val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
                owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) } }
            val running by LinkState.running.collectAsState()
            val addr by LinkState.address.collectAsState()
            val sessions by LinkState.sessions.collectAsState()
            val pairing by LinkState.pairing.collectAsState()
            val tick by LinkState.auditTick.collectAsState()
            var confirmRotate by remember { mutableStateOf(false) }
            @Suppress("UNUSED_VARIABLE") val r = refresh + tick
            Box(Modifier.fillMaxSize().background(Neon.BG)) {
                CodeRain()
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NeonTitle("TETHER")
                    MonoText("> native phone control · home Wi-Fi only · v${BuildConfig.VERSION_NAME}", Neon.DIM, 12)

                    Panel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoText(if (running) "● link ON  ${addr ?: "(no Wi-Fi address)"}" else "○ link OFF", if (running) Neon.PRIMARY else Neon.DIM)
                        MonoText("sessions: ${sessions.size}", Neon.BODY, 12)
                        NeonButton(if (store.enabled) "TURN OFF" else "TURN ON", if (store.enabled) Neon.DANGER else Neon.PRIMARY) {
                            store.enabled = !store.enabled
                            if (store.enabled) LinkService.start(this@MainActivity) else LinkService.stop(this@MainActivity); refresh++ }
                    } }

                    Panel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoText("PAIRED PCS", Neon.CYAN, 13)
                        val pcs = store.pcs()
                        if (pcs.isEmpty()) MonoText("none yet", Neon.DIM, 12)
                        pcs.forEach { pc -> Row(verticalAlignment = Alignment.CenterVertically) {
                            MonoText("${pc.name}  ${if (sessions.containsKey(pc.pcId)) "[live]" else ""}", Neon.BODY, 13, Modifier.weight(1f))
                            NeonButton("unlink", Neon.DANGER) { store.revoke(pc.pcId); refresh++ } } }
                        val p = pairing
                        when {
                            p == null -> NeonButton("PAIR NEW PC") { if (!store.enabled) { store.enabled = true; LinkService.start(this@MainActivity) }; LinkState.openPairing() }
                            p.result != null -> { MonoText(p.result, Neon.PRIMARY); NeonButton("done") { LinkState.closePairing() } }
                            p.awaitingAllow -> { MonoText("PC \"${p.pcName}\" typed the code. Allow it?", Neon.BODY)
                                Row { NeonButton("ALLOW") { LinkState.decision?.complete(true) }; Spacer(Modifier.width(12.dp)); NeonButton("DENY", Neon.DANGER) { LinkState.decision?.complete(false) } } }
                            p.code != null -> { MonoText("Type this on the PC:", Neon.DIM); NeonTitle(p.code, Neon.PRIMARY, 40); MonoText("If the PC says it doesn't match, tap cancel.", Neon.DIM, 11)
                                NeonButton("cancel", Neon.DANGER) { LinkState.closePairing() } }
                            else -> { MonoText("On the PC run:  plink pair --host ${addr?.substringBefore(':') ?: "<phone-ip>"}", Neon.BODY, 12)
                                MonoText("open for 3 minutes", Neon.DIM, 11); NeonButton("cancel", Neon.DANGER) { LinkState.closePairing() } }
                        }
                    } }

                    Panel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoText("PERMISSIONS", Neon.CYAN, 13)
                        val g = actions.permissions()
                        PermRow("Contacts (find_contact)", g.optBoolean("contacts")) { askPerms.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                        PermRow("Phone: call / hang up / call state", g.optBoolean("call_phone") && g.optBoolean("answer_calls") && g.optBoolean("phone_state")) {
                            askPerms.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)) }
                        PermRow("Notifications (session alerts)", g.optBoolean("post_notifications")) { askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                        PermRow("Notification access (read/dismiss, media)", g.optBoolean("notification_access")) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        PermRow("Display over other apps (call, alarms, apps, URLs)", g.optBoolean("display_over_apps")) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        PermRow("Battery unrestricted", g.optBoolean("battery_unrestricted")) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    } }

                    Panel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoText("PRIME KEY", Neon.CYAN, 13)
                        MonoText("Every command must carry this phone-made key. Rotating it cuts off all PCs until they pair again.", Neon.DIM, 11)
                        if (!confirmRotate) NeonButton("ROTATE KEY", Neon.AMBER) { confirmRotate = true }
                        else Row { NeonButton("YES, ROTATE", Neon.DANGER) { store.rotateToken(); confirmRotate = false }; Spacer(Modifier.width(12.dp)); NeonButton("no") { confirmRotate = false } }
                    } }

                    Panel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { MonoText("AUDIT (metadata only)", Neon.CYAN, 13, Modifier.weight(1f)); NeonButton("clear", Neon.DIM) { audit.clear(); refresh++ } }
                        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        val es = audit.entries().take(40)
                        if (es.isEmpty()) MonoText("nothing yet", Neon.DIM, 12)
                        es.forEach { e -> MonoText("${fmt.format(Date(e.time))}  ${e.tool} ${e.args}  → ${e.outcome}", if (e.outcome == "ok" || e.outcome == "paired") Neon.BODY else Neon.AMBER, 11) }
                    } }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoText((if (ok) "✓ " else "✗ ") + label, if (ok) Neon.PRIMARY else Neon.AMBER, 12, Modifier.weight(1f))
        if (!ok) NeonButton("grant", Neon.AMBER, onClick = onFix)
    }
}
