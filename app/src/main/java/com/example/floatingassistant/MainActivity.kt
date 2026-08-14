//package com.example.floatingassistant
//
//import android.content.ComponentName
//import android.content.Intent
//import android.os.Bundle
//import android.provider.Settings
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.lifecycle.lifecycleScope
//import androidx.compose.animation.AnimatedContent
//import androidx.compose.animation.animateColorAsState
//import androidx.compose.animation.core.tween
//import androidx.compose.animation.fadeIn
//import androidx.compose.animation.fadeOut
//import androidx.compose.animation.togetherWith
//import androidx.compose.foundation.background
//import androidx.compose.foundation.border
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.draw.scale
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.text.style.TextAlign
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import com.example.floatingassistant.ui.theme.FloatingAssistantTheme
//
//class MainActivity : ComponentActivity() {
//
//    // ── Single source of truth for permission, updated in onResume ──────────
//    // Using Activity-level mutableStateOf so setContent is called only ONCE.
//    // onResume just flips the value → Compose recomposes automatically.
//    private val hasPermission = mutableStateOf(false)
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        hasPermission.value = isAccessibilityServiceEnabled()
//
//        // ── Capture device/OS info at the permission step ────────────────────
//        // Runs whenever MainActivity is created, i.e. right as the user reaches
//        // the permission screen (or the main screen, if already granted).
//        DeviceInfoWriter.writeAsync(
//            context = this,
//            scope = lifecycleScope,
//            step = if (hasPermission.value) "already_granted" else "permission_screen_shown"
//        )
//
//        setContent {
//            FloatingAssistantTheme {
//                val permission by hasPermission
//                var isOn by remember { mutableStateOf(ServiceStateManager.isServiceEnabled.value) }
//
//                AppRoot(
//                    hasPermission = permission,
//                    isOn = isOn,
//                    onGrantPermission = {
//                        // Refresh the device-info snapshot at the exact moment the
//                        // user taps to grant the accessibility permission.
//                        DeviceInfoWriter.writeAsync(
//                            context = this,
//                            scope = lifecycleScope,
//                            step = "permission_requested"
//                        )
//                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
//                    },
//                    onToggle = { v ->
//                        isOn = v
//                        ServiceStateManager.setEnabled(v)
//
//                        // ── Phase 7: tie the floating overlay bubble to the same switch ──
//                        if (v) {
//                            if (FloatingOverlayService.hasOverlayPermission(this)) {
//                                FloatingOverlayService.start(this)
//                            } else {
//                                // SYSTEM_ALERT_WINDOW is a special permission — must be
//                                // granted from Settings, not a runtime dialog.
//                                startActivity(FloatingOverlayService.overlayPermissionIntent(this))
//                            }
//                        } else {
//                            FloatingOverlayService.stop(this)
//                        }
//                    }
//                )
//            }
//        }
//    }
//
//    override fun onResume() {
//        super.onResume()
//        // Re-check every time we return (e.g. from Accessibility settings).
//        val nowGranted = isAccessibilityServiceEnabled()
//        if (nowGranted && !hasPermission.value) {
//            // Permission just got granted → refresh the device-info snapshot
//            // one more time to record the state at the moment of grant.
//            DeviceInfoWriter.writeAsync(
//                context = this,
//                scope = lifecycleScope,
//                step = "permission_granted"
//            )
//        }
//        hasPermission.value = nowGranted
//
//        // Phase 7: if the user just granted "draw over other apps" from Settings
//        // and the capture switch is already ON, start the bubble now.
//        if (ServiceStateManager.isServiceEnabled.value && FloatingOverlayService.hasOverlayPermission(this)) {
//            FloatingOverlayService.start(this)
//        }
//    }
//
//    // ── Check if our service is enabled in system Accessibility settings ────
//    private fun isAccessibilityServiceEnabled(): Boolean {
//        val target = ComponentName(
//            packageName,
//            "$packageName.UiTreeAccessibilityService"
//        ).flattenToString()
//
//        val raw = Settings.Secure.getString(
//            contentResolver,
//            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
//        ) ?: return false
//
//        return raw.split(':').any { it.equals(target, ignoreCase = true) }
//    }
//}
//
//// ═══════════════════════════════════════════════════════════════════════════════
//// Design tokens  —  matches reference: near-black bg, white type, teal accent
//// ═══════════════════════════════════════════════════════════════════════════════
//
//private val BgScreen    = Color(0xFF141414)
//private val BgCard      = Color(0xFF1E1E1E)
//private val BgPill      = Color(0xFF2A2A2A)
//private val Teal        = Color(0xFF00D4C0)
//private val TealDim     = Color(0xFF00D4C0).copy(alpha = 0.15f)
//private val White       = Color(0xFFFFFFFF)
//private val Gray400     = Color(0xFF8A8A8A)
//private val Gray200     = Color(0xFFD4D4D4)
//private val Border      = Color(0xFF2A2A2A)
//private val GreenOk     = Color(0xFF34C759)   // iOS-style green for "granted"
//
//// ═══════════════════════════════════════════════════════════════════════════════
//// Root — animated crossfade between Permission and Main screens
//// ═══════════════════════════════════════════════════════════════════════════════
//
//@Composable
//private fun AppRoot(
//    hasPermission: Boolean,
//    isOn: Boolean,
//    onGrantPermission: () -> Unit,
//    onToggle: (Boolean) -> Unit
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(BgScreen)
//            .systemBarsPadding()
//    ) {
//        AnimatedContent(
//            targetState = hasPermission,
//            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
//            label = "screenSwitch"
//        ) { granted ->
//            if (granted) {
//                MainSwitchScreen(isOn = isOn, onToggle = onToggle)
//            } else {
//                PermissionScreen(onGrant = onGrantPermission)
//            }
//        }
//    }
//}
//
//// ═══════════════════════════════════════════════════════════════════════════════
//// Permission Screen
//// ═══════════════════════════════════════════════════════════════════════════════
//
//@Composable
//private fun PermissionScreen(onGrant: () -> Unit) {
//    Column(
//        modifier = Modifier
//            .fillMaxSize()
//            .padding(horizontal = 32.dp),
//        horizontalAlignment = Alignment.CenterHorizontally
//    ) {
//        Spacer(Modifier.weight(1f))
//
//        // ── Icon placeholder ─────────────────────────────────────────────────
//        Box(
//            modifier = Modifier
//                .size(80.dp)
//                .clip(RoundedCornerShape(24.dp))
//                .background(BgCard)
//                .border(1.dp, Border, RoundedCornerShape(24.dp)),
//            contentAlignment = Alignment.Center
//        ) {
//            Text("⚡", fontSize = 36.sp)
//        }
//
//        Spacer(Modifier.height(40.dp))
//
//        // ── Headline ─────────────────────────────────────────────────────────
//        Text(
//            text = "Enable\nAccessibility",
//            fontSize = 38.sp,
//            fontWeight = FontWeight.Bold,
//            color = White,
//            textAlign = TextAlign.Center,
//            lineHeight = 46.sp,
//            letterSpacing = (-1).sp
//        )
//
//        Spacer(Modifier.height(20.dp))
//
//        // ── Body ─────────────────────────────────────────────────────────────
//        Text(
//            text = "Floating Assistant reads the on-screen UI tree using Android's Accessibility API to capture and filter app interfaces.",
//            fontSize = 15.sp,
//            color = Gray400,
//            textAlign = TextAlign.Center,
//            lineHeight = 24.sp
//        )
//
//        Spacer(Modifier.weight(1f))
//
//        // ── CTA button ───────────────────────────────────────────────────────
//        Button(
//            onClick = onGrant,
//            modifier = Modifier
//                .fillMaxWidth()
//                .height(56.dp),
//            shape = RoundedCornerShape(50),
//            colors = ButtonDefaults.buttonColors(
//                containerColor = Teal,
//                contentColor   = Color(0xFF001412)
//            ),
//            elevation = ButtonDefaults.buttonElevation(0.dp)
//        ) {
//            Text(
//                text = "Open Settings",
//                fontWeight = FontWeight.SemiBold,
//                fontSize = 16.sp
//            )
//        }
//
//        Spacer(Modifier.height(16.dp))
//
//        Text(
//            text = "You can revoke access at any time in Settings.",
//            fontSize = 12.sp,
//            color = Gray400.copy(alpha = 0.6f),
//            textAlign = TextAlign.Center
//        )
//
//        Spacer(Modifier.height(40.dp))
//    }
//}
//
//// ═══════════════════════════════════════════════════════════════════════════════
//// Main Switch Screen
//// ═══════════════════════════════════════════════════════════════════════════════
//
//@Composable
//private fun MainSwitchScreen(isOn: Boolean, onToggle: (Boolean) -> Unit) {
//    val trackColor by animateColorAsState(
//        targetValue = if (isOn) Teal else BgPill,
//        animationSpec = tween(350),
//        label = "track"
//    )
//    val statusColor by animateColorAsState(
//        targetValue = if (isOn) Teal else Gray400,
//        animationSpec = tween(350),
//        label = "status"
//    )
//
//    Column(
//        modifier = Modifier
//            .fillMaxSize()
//            .padding(horizontal = 28.dp),
//        horizontalAlignment = Alignment.CenterHorizontally
//    ) {
//
//        Spacer(Modifier.height(56.dp))
//
//        // ── App name ─────────────────────────────────────────────────────────
//        Text(
//            text = "Floating\nAssistant",
//            fontSize = 40.sp,
//            fontWeight = FontWeight.Bold,
//            color = White,
//            lineHeight = 48.sp,
//            letterSpacing = (-1.5).sp
//        )
//
//        Spacer(Modifier.height(6.dp))
//
//        Text(
//            text = "UI Tree Capture Engine",
//            fontSize = 13.sp,
//            color = Gray400,
//            letterSpacing = 0.5.sp
//        )
//
//        Spacer(Modifier.weight(1f))
//
//        // ── Big toggle card ───────────────────────────────────────────────────
//        Box(
//            modifier = Modifier
//                .fillMaxWidth()
//                .clip(RoundedCornerShape(28.dp))
//                .background(BgCard)
//                .border(
//                    width = 1.dp,
//                    color = if (isOn) Teal.copy(alpha = 0.35f) else Border,
//                    shape = RoundedCornerShape(28.dp)
//                )
//                .padding(28.dp)
//        ) {
//            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
//
//                // Status badge
//                Row(
//                    verticalAlignment = Alignment.CenterVertically,
//                    horizontalArrangement = Arrangement.spacedBy(8.dp)
//                ) {
//                    Box(
//                        modifier = Modifier
//                            .size(8.dp)
//                            .clip(RoundedCornerShape(50))
//                            .background(statusColor)
//                    )
//                    Text(
//                        text = if (isOn) "Active" else "Idle",
//                        fontSize = 13.sp,
//                        fontWeight = FontWeight.Medium,
//                        color = statusColor,
//                        letterSpacing = 0.5.sp
//                    )
//                }
//
//                // Label + switch row
//                Row(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalArrangement = Arrangement.SpaceBetween,
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
//                        Text(
//                            text = "Capture Switch",
//                            fontSize = 22.sp,
//                            fontWeight = FontWeight.Bold,
//                            color = White,
//                            letterSpacing = (-0.5).sp
//                        )
//                        Text(
//                            text = if (isOn) "Reading UI tree…" else "Tap to start",
//                            fontSize = 14.sp,
//                            color = Gray400
//                        )
//                    }
//
//                    Switch(
//                        checked = isOn,
//                        onCheckedChange = onToggle,
//                        modifier = Modifier.scale(scale = 1.15f),
//                        colors = SwitchDefaults.colors(
//                            checkedThumbColor    = Color(0xFF001412),
//                            checkedTrackColor    = Teal,
//                            checkedBorderColor   = Teal,
//                            uncheckedThumbColor  = Gray400,
//                            uncheckedTrackColor  = BgPill,
//                            uncheckedBorderColor = Border
//                        )
//                    )
//                }
//            }
//        }
//
//        Spacer(Modifier.height(16.dp))
//
//        // ── Info pills row ────────────────────────────────────────────────────
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalArrangement = Arrangement.spacedBy(12.dp)
//        ) {
//            InfoPill(
//                label = "Permission",
//                value = "Granted",
//                dot = GreenOk,
//                modifier = Modifier.weight(1f)
//            )
//            InfoPill(
//                label = "Filter",
//                value = if (isOn) "Active" else "Standby",
//                dot = if (isOn) Teal else Gray400,
//                modifier = Modifier.weight(1f)
//            )
//        }
//
//        Spacer(Modifier.weight(1f))
//
//        // ── Footer note ───────────────────────────────────────────────────────
//        Text(
//            text = "Service runs in the background while enabled.\nNo data leaves your device.",
//            fontSize = 12.sp,
//            color = Gray400.copy(alpha = 0.55f),
//            textAlign = TextAlign.Center,
//            lineHeight = 18.sp
//        )
//
//        Spacer(Modifier.height(40.dp))
//    }
//}
//
//// ═══════════════════════════════════════════════════════════════════════════════
//// Info pill
//// ═══════════════════════════════════════════════════════════════════════════════
//
//@Composable
//private fun InfoPill(
//    label: String,
//    value: String,
//    dot: Color,
//    modifier: Modifier = Modifier
//) {
//    Row(
//        modifier = modifier
//            .clip(RoundedCornerShape(50))
//            .background(BgCard)
//            .border(1.dp, Border, RoundedCornerShape(50))
//            .padding(horizontal = 16.dp, vertical = 12.dp),
//        horizontalArrangement = Arrangement.spacedBy(8.dp),
//        verticalAlignment = Alignment.CenterVertically
//    ) {
//        Box(
//            modifier = Modifier
//                .size(7.dp)
//                .clip(RoundedCornerShape(50))
//                .background(dot)
//        )
//        Column {
//            Text(label, fontSize = 10.sp, color = Gray400, letterSpacing = 0.5.sp)
//            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Gray200)
//        }
//    }
//}


package com.example.floatingassistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
        hasPermission.value = isAccessibilityServiceEnabled()

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
        val nowGranted = isAccessibilityServiceEnabled()
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
                text = "Floating Assistant",
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
            text = "Floating Assistant needs Accessibility access to read on-screen elements before it can guide you.",
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