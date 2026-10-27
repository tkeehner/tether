package com.tyler.primelink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyler.primelink.actions.CallAudio
import com.tyler.primelink.actions.CallAudioFallback
import com.tyler.primelink.actions.CallCountdown
import kotlinx.coroutines.delay

/** "CALLING MOM… 3 2 1" with a big CANCEL. Shows over the lock screen; dials only if nobody cancels within 3 s. */
class CallCountdownActivity : ComponentActivity() {
    private var pending: CallCountdown.Pending? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val p = CallCountdown.current
        if (p == null) { finish(); return }
        pending = p
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { cancel() } })
        setContent {
            var left by remember { mutableIntStateOf(((CallCountdown.COUNTDOWN_MS + 999) / 1000).toInt()) }
            LaunchedEffect(p) {
                val end = p.startedAt + CallCountdown.COUNTDOWN_MS
                while (true) {
                    val rem = end - System.currentTimeMillis()
                    if (p.result.isDone) { finish(); return@LaunchedEffect }
                    if (rem <= 0) break
                    left = ((rem + 999) / 1000).toInt(); delay(minOf(rem, 100))
                }
                dial(p)
            }
            Box(Modifier.fillMaxSize().background(Neon.BG)) {
                CodeRain(alpha = 0.22f)
                Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    MonoText("> tether", Neon.DIM, 13)
                    Spacer(Modifier.height(10.dp))
                    NeonTitle("CALLING", Neon.CYAN, 22)
                    Spacer(Modifier.height(6.dp))
                    NeonTitle((p.name ?: p.number).uppercase().take(24), Neon.PRIMARY, 34)
                    if (p.name != null) MonoText(p.number, Neon.DIM, 14)
                    Spacer(Modifier.height(36.dp))
                    Text("$left", style = TextStyle(fontFamily = Neon.Title, fontSize = 96.sp, color = Neon.PRIMARY, shadow = glow(Neon.PRIMARY, 30f)))
                    Spacer(Modifier.height(44.dp))
                    NeonButton("CANCEL", Neon.DANGER) { cancel() }
                }
            }
        }
    }

    private fun cancel() { pending?.let { CallCountdown.cancelThis(it) }; finish() }

    private fun dial(p: CallCountdown.Pending) {
        val number = CallCountdown.claim(p) ?: run { finish(); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { CallCountdown.finish(p, "failed: CALL_PHONE not granted"); finish(); return }
        // Tell Telecom explicitly: audio-only, NOT speakerphone (unless Prime asked for speaker). Record the dial first so
        // CallWatchService can recognise this call as Prime's the moment Telecom adds it.
        CallAudio.notePlaced(number, p.speaker)
        val placed = CallAudio.lastPlaced
        val r = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, p.speaker)
                .putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (r.isSuccess && placed != null) CallAudioFallback.start(this, placed)
        CallCountdown.finish(p, if (r.isSuccess) "dialed" else "failed: ${r.exceptionOrNull()?.message}")
        finish()
    }

    override fun onDestroy() {
        // Leaving the screen any other way (Home, swiping it away) counts as cancel; never dial from the background.
        pending?.let { if (!it.result.isDone) CallCountdown.cancelThis(it) }
        super.onDestroy()
    }
}
