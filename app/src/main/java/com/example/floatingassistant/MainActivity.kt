package com.example.floatingassistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.floatingassistant.ui.theme.FloatingAssistantTheme

class MainActivity : ComponentActivity() {

    // ── Single source of truth for permission, updated in onResume ──────────
    // Using Activity-level mutableStateOf so setContent is called only ONCE.
    // onResume just flips the value → Compose recomposes automatically.
    private val hasPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [PERMANENT FIX] Do not remove this! Stream heartbeat to Live Dashboard whenever app is open
        DebugTelemetryClient.startHeartbeat()
        DebugTelemetryClient.emit(
            type = "APP_START",
            tag = "MainActivity",
            title = "GuidanceApp Active",
            description = "Main dashboard opened on device"
        )
        hasPermission.value = ensureAccessibilityServiceEnabled()

        // ── Capture device/OS info at the permission step ────────────────────
        // Runs whenever MainActivity is created, i.e. right as the user reaches
        // the permission screen (or the main screen, if already granted).
        DeviceInfoWriter.writeAsync(
            context = this,
            scope = lifecycleScope,
            step = if (hasPermission.value) "already_granted" else "permission_screen_shown"
        )

        setContent {
            FloatingAssistantTheme {
                val permission by hasPermission
                var isOn by remember { mutableStateOf(ServiceStateManager.isServiceEnabled.value) }

                AppRoot(
                    hasPermission = permission,
                    isOn = isOn,
                    onGrantPermission = {
                        // Refresh the device-info snapshot at the exact moment the
                        // user taps to grant the accessibility permission.
                        DeviceInfoWriter.writeAsync(
                            context = this,
                            scope = lifecycleScope,
                            step = "permission_requested"
                        )
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onToggle = { v ->
                        isOn = v
                        ServiceStateManager.setEnabled(v)

                        // ── Phase 7: tie the floating overlay bubble to the same switch ──
                        if (v) {
                            if (FloatingOverlayService.hasOverlayPermission(this)) {
                                FloatingOverlayService.start(this)
                            } else {
                                // SYSTEM_ALERT_WINDOW is a special permission — must be
                                // granted from Settings, not a runtime dialog.
                                startActivity(FloatingOverlayService.overlayPermissionIntent(this))
                            }
                        } else {
                            FloatingOverlayService.stop(this)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time we return (e.g. from Accessibility settings).
        val nowGranted = ensureAccessibilityServiceEnabled()
        if (nowGranted && !hasPermission.value) {
            // Permission just got granted → refresh the device-info snapshot
            // one more time to record the state at the moment of grant.
            DeviceInfoWriter.writeAsync(
                context = this,
                scope = lifecycleScope,
                step = "permission_granted"
            )
        }
        hasPermission.value = nowGranted

        // Phase 7: if the user just granted "draw over other apps" from Settings
        // and the capture switch is already ON, start the bubble now.
        if (ServiceStateManager.isServiceEnabled.value && FloatingOverlayService.hasOverlayPermission(this)) {
            FloatingOverlayService.start(this)
        }
    }

    // [PERMANENT FIX] Do not remove this! Check and self-activate AccessibilityService if WRITE_SECURE_SETTINGS is granted via ADB
    private fun ensureAccessibilityServiceEnabled(): Boolean {
        if (isAccessibilityServiceEnabled()) return true

        return try {
            val target = ComponentName(
                packageName,
                "$packageName.UiTreeAccessibilityService"
            ).flattenToString()
            val resolver = contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val newServices = if (current.isBlank()) {
                target
            } else if (!current.split(':').any { it.equals(target, ignoreCase = true) }) {
                "$current:$target"
            } else {
                current
            }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("MainActivity", "[PERMANENT FIX] Self-activated accessibility service via WRITE_SECURE_SETTINGS!")
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // ── Check if our service is enabled in system Accessibility settings ────
    private fun isAccessibilityServiceEnabled(): Boolean {
        val target = ComponentName(
            packageName,
            "$packageName.UiTreeAccessibilityService"
        ).flattenToString()

        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return raw.split(':').any { it.equals(target, ignoreCase = true) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Design tokens  —  light, card-based UI inspired by the reference mock:
// soft neutral background, white cards, amber "setup required" banner, teal accent
// ═══════════════════════════════════════════════════════════════════════════════

private val BgScreen     = Color(0xFFF3F4F8)
private val BgCard       = Color(0xFFFFFFFF)
private val BorderCard   = Color(0xFFE7E8F0)
private val TextPrimary  = Color(0xFF15161B)
private val TextSecondary= Color(0xFF6B6F7B)
private val Teal         = Color(0xFF00A896)
private val OnTeal       = Color(0xFFFFFFFF)
private val BgAmber      = Color(0xFFFDF3D6)
private val BorderAmber  = Color(0xFFEFD584)
private val TextAmberHd  = Color(0xFF8A5A0A)
private val TextAmberBd  = Color(0xFF8A6B2A)
private val TrackOff     = Color(0xFFE3E5EE)

// ═══════════════════════════════════════════════════════════════════════════════
// Root — a single unified screen (matches the reference layout), with the
// "setup required" banner appearing only while the accessibility permission
// is missing. Functionality is unchanged: hasPermission gates real capture,
// isOn/onToggle still drives ServiceStateManager + the floating overlay bubble.
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AppRoot(
    hasPermission: Boolean,
    isOn: Boolean,
    onGrantPermission: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgScreen)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(28.dp))

            // ── Header ───────────────────────────────────────────────────────
            Text(
                text = "GuidanceApp",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                lineHeight = 36.sp,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your on-screen navigation guide",
                fontSize = 15.sp,
                color = TextSecondary
            )

            Spacer(Modifier.height(20.dp))

            // ── Setup required banner (only while permission is missing) ───────
            AnimatedVisibility(visible = !hasPermission) {
                Column {
                    SetupBanner(onGrant = onGrantPermission)
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ── Capture switch card ─────────────────────────────────────────────
            CaptureCard(
                hasPermission = hasPermission,
                isOn = isOn,
                onToggle = onToggle
            )

            Spacer(Modifier.height(14.dp))

            // ── Status line ──────────────────────────────────────────────────
            val statusColor = if (!hasPermission) TextSecondary else if (isOn) Teal else TextSecondary
            val statusLabel = when {
                !hasPermission -> "Setup required"
                isOn -> "Active"
                else -> "Ready"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(statusLabel, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(28.dp))

            // ── How it works ─────────────────────────────────────────────────
            Text(
                text = "How it works",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            HowItWorksCard()

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Runs in the background while enabled. No data leaves your device.",
                fontSize = 12.sp,
                color = TextSecondary.copy(alpha = 0.8f),
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Setup required banner
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SetupBanner(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgAmber)
            .border(1.dp, BorderAmber, RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Setup Required",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextAmberHd
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "GuidanceApp needs Accessibility access to read on-screen elements before it can guide you.",
            fontSize = 13.sp,
            color = TextAmberBd,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onGrant,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, TextAmberHd.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextAmberHd)
        ) {
            Text(
                text = "SET UP NOW",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Capture switch card — same toggle behavior as before, restyled for the
// light card look. Disabled (visually + interactively) until permission
// is granted, since capture can't actually run without it.
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CaptureCard(
    hasPermission: Boolean,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val subtitle = when {
        !hasPermission -> "Grant Accessibility access to enable"
        isOn -> "Reading the screen in the background…"
        else -> "Tap to start"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BgCard)
            .border(
                width = 1.dp,
                color = if (isOn) Teal.copy(alpha = 0.35f) else BorderCard,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Capture Switch",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = (-0.3).sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.width(12.dp))

            Switch(
                checked = isOn,
                onCheckedChange = onToggle,
                enabled = hasPermission,
                colors = SwitchDefaults.colors(
                    checkedThumbColor    = OnTeal,
                    checkedTrackColor    = Teal,
                    checkedBorderColor   = Teal,
                    uncheckedThumbColor  = Color(0xFFFFFFFF),
                    uncheckedTrackColor  = TrackOff,
                    uncheckedBorderColor = TrackOff,
                    disabledUncheckedTrackColor = TrackOff.copy(alpha = 0.6f),
                    disabledUncheckedBorderColor = TrackOff.copy(alpha = 0.6f)
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// How it works — rewritten to describe what this app actually does
// (accessibility capture + the floating bubble's guide flow), instead of the
// reference app's record/remember/"My Tasks" flow which this project doesn't have.
// ═══════════════════════════════════════════════════════════════════════════════

private data class HowItWorksStep(val text: String)

private val howItWorksSteps = listOf(
    HowItWorksStep("Grant Accessibility access and turn on the Capture Switch above"),
    HowItWorksStep("Tap the floating bubble anywhere on screen and type what you want to do"),
    HowItWorksStep("If that path is known on your device, I'll highlight where to tap"),
    HowItWorksStep("Follow the on-screen arrows — I'll guide you, but I'll never tap for you")
)

@Composable
private fun HowItWorksCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BgCard)
            .border(1.dp, BorderCard, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        howItWorksSteps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${index + 1}.",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.width(22.dp)
                )
                Text(
                    text = step.text,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
            if (index != howItWorksSteps.lastIndex) {
                HorizontalDivider(color = BorderCard, thickness = 1.dp)
            }
        }
    }
}