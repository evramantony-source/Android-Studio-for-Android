package com.example.emulator

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import com.example.model.ProjectFile
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private data class DrawnStroke(
    val color: Color,
    val points: List<Offset>,
)

@Composable
fun EmulatorPanel(
    appName: String,
    packageName: String,
    files: List<ProjectFile>,
    builtApkPath: String?,
    onInstallApk: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var profile by remember { mutableStateOf(EmulatorDeviceProfile.PIXEL_PRO) }
    var isLandscape by remember { mutableStateOf(false) }
    var restartNonce by remember { mutableIntStateOf(0) }
    var showLogcat by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<String?>(null) }
    var fallbackDialogMessage by remember { mutableStateOf<String?>(null) }
    var vmFrameTick by remember { mutableIntStateOf(0) }

    var liveSession by remember(appName, packageName, restartNonce) { mutableStateOf<LiveVmSession?>(null) }
    var program by remember(appName, packageName, restartNonce) { mutableStateOf<EmulatorProgram?>(null) }
    var isBootingVm by remember(appName, packageName, restartNonce) { mutableStateOf(true) }

    val runtimeState = remember(restartNonce) {
        mutableStateMapOf<String, String>()
    }
    val strokes = remember(restartNonce) { mutableStateListOf<DrawnStroke>() }
    val activeStrokePoints = remember(restartNonce) { mutableStateListOf<Offset>() }
    val logcat = remember(packageName, restartNonce) {
        mutableStateListOf("I/ActivityTaskManager: COLD BOOT u0 {cmp=$packageName/.MainActivity}")
    }

    // Guarantee fresh cold boot from frame 0 / onCreate every time the emulator is entered
    DisposableEffect(Unit) {
        restartNonce++
        onDispose {
            liveSession = null
            vmFrameTick = 0
        }
    }

    fun appendLog(line: String) {
        logcat.add(line)
        while (logcat.size > 120) logcat.removeAt(0)
    }

    fun syncFromLiveVm(targetSession: LiveVmSession? = liveSession) {
        val session = targetSession ?: return
        session.drainLogs().forEach(::appendLog)
        session.drainLatestToast()?.let { latest ->
            toastText = latest
        }
    }

    LaunchedEffect(appName, packageName, files, restartNonce) {
        isBootingVm = true
        liveSession = null
        toastText = null
        fallbackDialogMessage = null
        vmFrameTick = 0
        strokes.clear()
        activeStrokePoints.clear()
        runtimeState.clear()
        logcat.clear()
        appendLog("I/ActivityTaskManager: COLD BOOT u0 {cmp=$packageName/.MainActivity}")
        yield()
        val bootResult = withContext(Dispatchers.Default) {
            runCatching {
                val session = EmulatorEngine.createLiveSession(appName, packageName, files)?.let { s ->
                    if (s.start()) s else null
                }
                val prog = EmulatorEngine.parseProject(appName, packageName, files)
                session to prog
            }
        }
        val (bootedSession, parsedProgram) = bootResult.getOrElse { err ->
            if (err is kotlinx.coroutines.CancellationException) throw err
            appendLog("E/AndroidStudioVM: Boot error: ${err.message ?: err.javaClass.simpleName}")
            null to EmulatorEngine.parseProject(appName, packageName, emptyList())
        }
        liveSession = bootedSession
        program = parsedProgram
        runtimeState.clear()
        runtimeState.putAll(parsedProgram.initialState)
        if (bootedSession != null) {
            appendLog("D/AndroidStudioVM: Cold-booted Live AST VM (${files.size} project file(s))")
            syncFromLiveVm(bootedSession)
        } else {
            appendLog("D/AndroidStudioVM: Fallback layout (${parsedProgram.detectedWidgetsCount} widgets)")
        }
        isBootingVm = false
    }

    // 60fps Live VM frame pump only while timers/animations are actively queued
    LaunchedEffect(liveSession, vmFrameTick, restartNonce) {
        val session = liveSession ?: return@LaunchedEffect
        syncFromLiveVm(session)
        while (isActive && session.hasPendingTimers()) {
            withFrameNanos { _ ->
                val ticked = runCatching {
                    session.tickTimers(System.currentTimeMillis())
                }.onFailure { err ->
                    appendLog("E/AndroidStudioVM: Timer error: ${err.message ?: err.javaClass.simpleName}")
                }.getOrDefault(false)
                if (ticked) {
                    vmFrameTick++
                    syncFromLiveVm(session)
                }
            }
        }
    }

    val vmSnapshot = remember(liveSession, vmFrameTick, restartNonce) {
        runCatching { liveSession?.buildSnapshot() }.onFailure { err ->
            appendLog("E/DroidForgeVM: Render error: ${err.message ?: err.javaClass.simpleName}")
        }.getOrNull()
    }

    // Fallback timer coroutine when Live VM is absent & static PulseTimer fallback is active
    val timerRunning = !isBootingVm && liveSession == null && runtimeState["timerRunning"] == "true"
    LaunchedEffect(timerRunning, restartNonce) {
        while (timerRunning) {
            delay(100L)
            val tenths = (runtimeState["timerTenths"]?.toIntOrNull() ?: 0) + 1
            runtimeState["timerTenths"] = tenths.toString()
            val totalSeconds = tenths / 10
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            val frac = tenths % 10
            runtimeState["timerDisplay"] = "%02d:%02d.%d".format(mins, secs, frac)
        }
    }

    // Auto-dismiss Android Toast preview
    LaunchedEffect(toastText) {
        if (toastText != null) {
            delay(2200L)
            toastText = null
        }
    }

    fun handleFallbackAction(kind: EmulatorActionKind, arg: String) {
        when (kind) {
            EmulatorActionKind.INCREMENT -> {
                val next = (runtimeState["count"]?.toIntOrNull() ?: 0) + 1
                runtimeState["count"] = next.toString()
                appendLog("D/OnClick: +1 -> count=$next")
            }
            EmulatorActionKind.DECREMENT -> {
                val next = (runtimeState["count"]?.toIntOrNull() ?: 0) - 1
                runtimeState["count"] = next.toString()
                appendLog("D/OnClick: -1 -> count=$next")
            }
            EmulatorActionKind.RESET_COUNTER -> {
                runtimeState["count"] = "0"
                toastText = arg.ifBlank { "Counter reset" }
                appendLog("I/Toast: ${toastText.orEmpty()}")
            }
            EmulatorActionKind.BRUSH_COLOR -> {
                runtimeState["brushColor"] = arg
                appendLog("D/DrawingPad: brushColor=$arg")
            }
            EmulatorActionKind.CLEAR_CANVAS -> {
                strokes.clear()
                activeStrokePoints.clear()
                appendLog("D/DrawingPad: clearCanvas()")
            }
            EmulatorActionKind.CALC_KEY -> {
                val currentDisp = runtimeState["calcDisplay"] ?: "0"
                val first = runtimeState["calcFirst"]?.toDoubleOrNull() ?: 0.0
                val op = runtimeState["calcOp"].orEmpty()
                val clearNext = runtimeState["calcClearNext"] == "true"
                when {
                    arg == "C" -> {
                        runtimeState["calcDisplay"] = "0"
                        runtimeState["calcFirst"] = "0"
                        runtimeState["calcOp"] = ""
                        runtimeState["calcClearNext"] = "true"
                    }
                    arg.length == 1 && arg[0].isDigit() -> {
                        val next = if (clearNext || currentDisp == "0") arg else currentDisp + arg
                        runtimeState["calcDisplay"] = next
                        runtimeState["calcClearNext"] = "false"
                    }
                    arg == "=" -> {
                        val second = currentDisp.toDoubleOrNull() ?: 0.0
                        val res = when (op) {
                            "+" -> first + second
                            "-" -> first - second
                            "*" -> first * second
                            "/" -> if (second == 0.0) 0.0 else first / second
                            else -> second
                        }
                        runtimeState["calcDisplay"] = if (res == res.toLong().toDouble()) {
                            res.toLong().toString()
                        } else {
                            "%.4f".format(res).trimEnd('0').trimEnd('.')
                        }
                        runtimeState["calcOp"] = ""
                        runtimeState["calcClearNext"] = "true"
                    }
                    else -> {
                        runtimeState["calcFirst"] = currentDisp
                        runtimeState["calcOp"] = arg
                        runtimeState["calcClearNext"] = "true"
                    }
                }
                appendLog("D/Calc: key='$arg' display=${runtimeState["calcDisplay"]}")
            }
            EmulatorActionKind.ADD_NOTE -> {
                val text = runtimeState["noteInput"].orEmpty().trim()
                if (text.isNotEmpty()) {
                    val existing = runtimeState["notesList"].orEmpty()
                        .split('\n')
                        .filter { it.isNotBlank() && !it.startsWith("Tap Save Note") }
                    val updated = listOf("• $text") + existing
                    runtimeState["notesList"] = updated.joinToString("\n")
                    runtimeState["noteInput"] = ""
                    toastText = "Note saved"
                    appendLog("I/Notes: Saved note (${updated.size} total)")
                }
            }
            EmulatorActionKind.TIMER_START_PAUSE -> {
                val next = !(runtimeState["timerRunning"] == "true")
                runtimeState["timerRunning"] = next.toString()
                appendLog("I/Timer: running=$next")
            }
            EmulatorActionKind.TIMER_LAP -> {
                val readout = runtimeState["timerDisplay"] ?: "00:00.0"
                val existing = runtimeState["laps"].orEmpty().split('\n').filter { it.isNotBlank() }
                val updated = listOf("Lap ${existing.size + 1}: $readout") + existing
                runtimeState["laps"] = updated.joinToString("\n")
                appendLog("D/Timer: Recorded $readout")
            }
            EmulatorActionKind.TIMER_RESET -> {
                runtimeState["timerRunning"] = "false"
                runtimeState["timerTenths"] = "0"
                runtimeState["timerDisplay"] = "00:00.0"
                runtimeState["laps"] = ""
                appendLog("I/Timer: Reset")
            }
            EmulatorActionKind.SHOW_TOAST -> {
                toastText = arg
                appendLog("I/Toast: $arg")
            }
            EmulatorActionKind.SHOW_DIALOG -> {
                fallbackDialogMessage = arg
                appendLog("I/AlertDialog: $arg")
            }
            EmulatorActionKind.NONE -> Unit
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Emulator Top Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Live Android Emulator",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val activeSessionBadge = liveSession
                        if (activeSessionBadge != null && vmSnapshot != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                val badgeText = if (activeSessionBadge.lastRenderOpsCount > 0) {
                                    "VM • ${activeSessionBadge.lastRenderOpsCount} ops"
                                } else {
                                    "LIVE VM"
                                }
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = "${profile.label} • ${if (isLandscape) "Landscape" else "Portrait"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { isLandscape = !isLandscape },
                    modifier = Modifier.testTag("emulator_rotate_button"),
                ) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate device")
                }
                IconButton(
                    onClick = { showLogcat = !showLogcat },
                    modifier = Modifier.testTag("emulator_logcat_button"),
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = "Toggle emulator logcat",
                        tint = if (showLogcat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { restartNonce++ },
                    modifier = Modifier.testTag("emulator_restart_button"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Cold Boot (Reset app from start)")
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("emulator_close_button")) {
                    Icon(Icons.Default.Close, contentDescription = "Close emulator")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Device Profile Chips + Real APK Install CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmulatorDeviceProfile.entries.forEach { item ->
                    FilterChip(
                        selected = profile == item,
                        onClick = { profile = item },
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
                if (!builtApkPath.isNullOrBlank() && builtApkPath.endsWith(".apk")) {
                    FilterChip(
                        selected = false,
                        onClick = { onInstallApk(builtApkPath) },
                        label = {
                            Text(
                                text = "Install APK",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            // Virtual Device Viewport
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = if (isLandscape) {
                    (profile.aspectHeight / profile.aspectWidth).coerceAtMost(2.1f)
                } else {
                    (profile.aspectWidth / profile.aspectHeight).coerceIn(0.45f, 0.85f)
                }
                val availW = maxWidth.coerceAtLeast(1.dp)
                val availH = maxHeight.coerceAtLeast(1.dp)
                val maxCapW = if (isLandscape) 560.dp else 440.dp
                val minW = minOf(120.dp, availW)
                val minH = minOf(140.dp, availH)
                val fittedWidth = minOf(availW, availH * ratio).coerceAtMost(maxCapW).coerceAtLeast(minW)
                val fittedHeight = (fittedWidth / ratio).coerceAtMost(availH).coerceAtLeast(minH)
                val frameModifier = Modifier
                    .width(fittedWidth)
                    .height(fittedHeight)

                // Realistic Virtual Device Bezel
                Surface(
                    color = Color(0xFF05070B),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 12.dp,
                    modifier = frameModifier
                        .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                        .padding(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp)),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Android Status Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0A0E16))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "12:00",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1F2937)),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                                    Icon(Icons.Default.BatteryFull, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(12.dp))
                                }
                            }

                            // Android ActionBar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = liveSession?.appName ?: program?.activityTitle ?: appName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // Activity Content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onSizeChanged { size ->
                                        val activeSession = liveSession
                                        if (activeSession != null && size.width > 0 && size.height > 0) {
                                            if (activeSession.updateViewportSize(size.width, size.height)) {
                                                syncFromLiveVm(activeSession)
                                                vmFrameTick++
                                            }
                                        }
                                    },
                            ) {
                                val currentSession = liveSession
                                val currentProgram = program
                                when {
                                    isBootingVm || currentProgram == null -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFF0B1120))
                                                .testTag("emulator_boot_loading"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.padding(16.dp),
                                            ) {
                                                CircularProgressIndicator(
                                                    color = Color(0xFF34D399),
                                                    strokeWidth = 3.dp,
                                                    modifier = Modifier.size(34.dp),
                                                )
                                                Text(
                                                    text = "Booting Live Android VM...",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    textAlign = TextAlign.Center,
                                                )
                                                Text(
                                                    text = "Compiling AST & Canvas runtime",
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 11.sp,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    }
                                    currentSession != null && vmSnapshot != null -> {
                                        RenderVmNode(
                                            node = vmSnapshot,
                                            session = currentSession,
                                            onMutate = {
                                                syncFromLiveVm()
                                                vmFrameTick++
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    else -> {
                                        RenderEmulatorNode(
                                            node = currentProgram.root,
                                            state = runtimeState,
                                            strokes = strokes,
                                            activeStrokePoints = activeStrokePoints,
                                            onAction = ::handleFallbackAction,
                                        )
                                    }
                                }

                                // Simulated Android Toast
                                toastText?.let { msg ->
                                    Surface(
                                        color = Color(0xE61E293B),
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 18.dp),
                                    ) {
                                        Text(
                                            text = msg,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }

                            // Gesture Nav Pill
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0A0E16))
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(56.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFF64748B)),
                                )
                            }
                        }
                    }
                }
            }

            // Collapsible Live Logcat
            AnimatedVisibility(visible = showLogcat) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Emulator Logcat", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { logcat.clear() }) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(logcat) { line ->
                                Text(
                                    text = line,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val vmDialog = liveSession?.activeDialog
    if (vmDialog != null) {
        val activeSession = liveSession
        AlertDialog(
            onDismissRequest = {
                activeSession?.dismissDialog(null)
                vmFrameTick++
            },
            title = { Text(vmDialog.first.ifBlank { program?.appName ?: appName }) },
            text = { Text(vmDialog.second) },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeSession?.dismissDialog(true)
                        syncFromLiveVm()
                        vmFrameTick++
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                if (activeSession?.activeDialogNegative != null) {
                    TextButton(
                        onClick = {
                            activeSession.dismissDialog(false)
                            syncFromLiveVm()
                            vmFrameTick++
                        },
                    ) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    fallbackDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { fallbackDialogMessage = null },
            title = { Text(program?.appName ?: appName) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { fallbackDialogMessage = null }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun RenderVmNode(
    node: VmViewNode,
    session: LiveVmSession,
    onMutate: () -> Unit,
    modifier: Modifier = Modifier,
    insideVerticalScroll: Boolean = false,
) {
    if (node.visibility == 8) return // View.GONE
    if (node.visibility == 4) { // View.INVISIBLE
        Spacer(modifier = modifier)
        return
    }

    val safeCornerDp = (if (node.cornerRadiusPx.isFinite()) node.cornerRadiusPx / 2.5f else 0f).coerceIn(0f, 24f)
    val shape = RoundedCornerShape(safeCornerDp.dp)
    val baseModifier = modifier
        .let { m ->
            val bg = node.backgroundColor
            if (bg != null) m.background(Color(bg), shape) else m
        }
        .padding(
            start = (node.paddingLeft / 3f).coerceIn(0f, 32f).dp,
            top = (node.paddingTop / 3f).coerceIn(0f, 32f).dp,
            end = (node.paddingRight / 3f).coerceIn(0f, 32f).dp,
            bottom = (node.paddingBottom / 3f).coerceIn(0f, 32f).dp,
        )

    when (node) {
        is VmViewNode.Container -> {
            when {
                node.kind == "GridLayout" -> {
                    val cols = node.columns.coerceAtLeast(1)
                    Column(
                        modifier = baseModifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        node.children.chunked(cols).forEach { rowChildren ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                rowChildren.forEach { child ->
                                    RenderVmNode(
                                        node = child,
                                        session = session,
                                        onMutate = onMutate,
                                        modifier = Modifier.weight(1f),
                                        insideVerticalScroll = insideVerticalScroll,
                                    )
                                }
                            }
                        }
                    }
                }

                node.kind == "FrameLayout" || node.kind == "RelativeLayout" -> {
                    Box(modifier = baseModifier) {
                        node.children.forEach { child ->
                            val childMod = when {
                                insideVerticalScroll && child is VmViewNode.CustomCanvasView ->
                                    Modifier.fillMaxWidth().height(260.dp)
                                insideVerticalScroll -> Modifier.fillMaxWidth()
                                else -> Modifier.fillMaxSize()
                            }
                            RenderVmNode(
                                node = child,
                                session = session,
                                onMutate = onMutate,
                                modifier = childMod,
                                insideVerticalScroll = insideVerticalScroll,
                            )
                        }
                    }
                }

                !node.orientationVertical -> {
                    val centeredH = (node.gravity and 0x01) != 0 || (node.gravity and 0x11) == 0x11
                    val anyRowWeight = node.children.any { it.layoutWeight.isFinite() && it.layoutWeight > 0f && it.layoutWeight < 100f }
                    val rowMod = if (anyRowWeight) {
                        baseModifier.fillMaxWidth()
                    } else {
                        baseModifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    }
                    Row(
                        modifier = rowMod,
                        horizontalArrangement = if (centeredH) Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        node.children.forEach { child ->
                            val w = child.layoutWeight
                            RenderVmNode(
                                node = child,
                                session = session,
                                onMutate = onMutate,
                                modifier = if (w.isFinite() && w > 0f && w < 100f) Modifier.weight(w) else Modifier,
                                insideVerticalScroll = insideVerticalScroll,
                            )
                        }
                    }
                }

                else -> {
                    val isScroll = node.kind == "ScrollView"
                    val anyExplicitWeight = node.children.any { it.layoutWeight.isFinite() && it.layoutWeight > 0f }
                    val hasWeightedCustomChild = !isScroll && !insideVerticalScroll &&
                        node.children.any { (it.layoutWeight.isFinite() && it.layoutWeight > 0f) || it is VmViewNode.CustomCanvasView }
                    val centeredV = (node.gravity and 0x10) != 0 || (node.gravity and 0x11) == 0x11
                    val useScroll = !insideVerticalScroll && (isScroll || !hasWeightedCustomChild)
                    val childInsideScroll = insideVerticalScroll || isScroll || useScroll
                    val colModifier = if (useScroll) {
                        baseModifier.verticalScroll(rememberScrollState())
                    } else {
                        baseModifier
                    }
                    Column(
                        modifier = colModifier,
                        verticalArrangement = if (centeredV && !hasWeightedCustomChild) {
                            Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        } else {
                            Arrangement.spacedBy(8.dp)
                        },
                        horizontalAlignment = if ((node.gravity and 0x01) != 0) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        node.children.forEach { child ->
                            val w = child.layoutWeight
                            val validWeight = w.isFinite() && w > 0f && w < 100f
                            val childMod = when {
                                childInsideScroll && child is VmViewNode.CustomCanvasView -> Modifier.fillMaxWidth().height(320.dp)
                                childInsideScroll -> Modifier.fillMaxWidth()
                                validWeight -> Modifier.weight(w).fillMaxWidth()
                                child is VmViewNode.CustomCanvasView && !anyExplicitWeight -> Modifier.weight(1f).fillMaxWidth()
                                else -> Modifier
                            }
                            RenderVmNode(
                                node = child,
                                session = session,
                                onMutate = onMutate,
                                modifier = childMod,
                                insideVerticalScroll = childInsideScroll,
                            )
                        }
                    }
                }
            }
        }

        is VmViewNode.Text -> {
            val alignEnd = (node.gravity and 0x05) == 0x05 || (node.gravity and 0x800005) == 0x800005
            val alignCenter = (node.gravity and 0x01) != 0
            Text(
                text = node.text,
                fontSize = (node.textSizeSp * 0.78f).coerceIn(9f, 52f).sp,
                color = node.textColor?.let { Color(it) } ?: Color.White,
                fontWeight = if (node.isBold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (node.isMonospace) FontFamily.Monospace else FontFamily.SansSerif,
                textAlign = when {
                    alignEnd -> TextAlign.End
                    alignCenter -> TextAlign.Center
                    else -> TextAlign.Start
                },
                modifier = baseModifier.fillMaxWidth().takeIf { node.backgroundColor != null || alignEnd || alignCenter }
                    ?: baseModifier,
            )
        }

        is VmViewNode.ButtonView -> {
            Button(
                onClick = {
                    session.dispatchClick(node.id)
                    onMutate()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = node.backgroundColor?.let { Color(it) } ?: Color(0xFF0284C7),
                    contentColor = node.textColor?.let { Color(it) } ?: Color.White,
                ),
                shape = RoundedCornerShape((node.cornerRadiusPx / 2.5f).coerceIn(6f, 18f).dp),
                modifier = modifier,
            ) {
                Text(
                    text = node.text.ifBlank { "Button" },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is VmViewNode.EditTextView -> {
            OutlinedTextField(
                value = node.text,
                onValueChange = { updated ->
                    session.dispatchEditTextChange(node.id, updated)
                    onMutate()
                },
                placeholder = { Text(node.hint, fontSize = 11.sp) },
                singleLine = true,
                modifier = modifier.fillMaxWidth(),
            )
        }

        is VmViewNode.CustomCanvasView -> {
            val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
            val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
            Canvas(
                modifier = baseModifier
                    .fillMaxWidth()
                    .onSizeChanged { s ->
                        if (s.width > 0 && s.height > 0) {
                            if (session.updateViewportSize(s.width, s.height)) {
                                onMutate()
                            }
                        }
                    }
                    .pointerInput(node.id, session) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val action = when (event.type) {
                                    PointerEventType.Press -> 0 // MotionEvent.ACTION_DOWN
                                    PointerEventType.Move -> 2 // MotionEvent.ACTION_MOVE
                                    PointerEventType.Release -> 1 // MotionEvent.ACTION_UP
                                    else -> null
                                }
                                if (action != null) {
                                    change.consume()
                                    runCatching {
                                        session.dispatchTouch(node.id, action, change.position.x, change.position.y)
                                        onMutate()
                                    }
                                }
                            }
                        }
                    },
            ) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val initialSave = native.save()
                    try {
                        native.clipRect(0f, 0f, size.width, size.height)

                        node.ops.forEach { op ->
                            when (op) {
                                is VmCanvasOp.FillColor -> {
                                    native.drawColor(op.argb)
                                }
                                is VmCanvasOp.DrawRect -> {
                                    val l = op.left.safeFinite()
                                    val t = op.top.safeFinite()
                                    val r = op.right.safeFinite()
                                    val b = op.bottom.safeFinite()
                                    val left = minOf(l, r)
                                    val right = maxOf(l, r)
                                    val top = minOf(t, b)
                                    val bottom = maxOf(t, b)
                                    val rad = op.radius.safeFinite(0f).coerceAtLeast(0f)
                                    paint.color = op.argb
                                    paint.style = if (op.isStroke) Paint.Style.STROKE else Paint.Style.FILL
                                    paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                    if (rad > 0f) {
                                        native.drawRoundRect(left, top, right, bottom, rad, rad, paint)
                                    } else {
                                        native.drawRect(left, top, right, bottom, paint)
                                    }
                                }
                                is VmCanvasOp.DrawCircle -> {
                                    val rad = op.radius.safeFinite(0f)
                                    if (rad > 0f) {
                                        paint.color = op.argb
                                        paint.style = if (op.isStroke) Paint.Style.STROKE else Paint.Style.FILL
                                        paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                        native.drawCircle(op.cx.safeFinite(), op.cy.safeFinite(), rad, paint)
                                    }
                                }
                                is VmCanvasOp.DrawOval -> {
                                    val l = op.left.safeFinite()
                                    val t = op.top.safeFinite()
                                    val r = op.right.safeFinite()
                                    val b = op.bottom.safeFinite()
                                    paint.color = op.argb
                                    paint.style = if (op.isStroke) Paint.Style.STROKE else Paint.Style.FILL
                                    paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                    native.drawOval(minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b), paint)
                                }
                                is VmCanvasOp.DrawLine -> {
                                    paint.color = op.argb
                                    paint.style = Paint.Style.STROKE
                                    paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                    paint.strokeCap = Paint.Cap.ROUND
                                    native.drawLine(op.x1.safeFinite(), op.y1.safeFinite(), op.x2.safeFinite(), op.y2.safeFinite(), paint)
                                }
                                is VmCanvasOp.DrawArc -> {
                                    val l = op.left.safeFinite()
                                    val t = op.top.safeFinite()
                                    val r = op.right.safeFinite()
                                    val b = op.bottom.safeFinite()
                                    paint.color = op.argb
                                    paint.style = if (op.isStroke) Paint.Style.STROKE else Paint.Style.FILL
                                    paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                    native.drawArc(
                                        minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b),
                                        op.startAngle.safeFinite(), op.sweepAngle.safeFinite(), op.useCenter, paint,
                                    )
                                }
                                is VmCanvasOp.DrawPath -> {
                                    if (op.segments.isNotEmpty()) {
                                        val androidPath = android.graphics.Path()
                                        op.segments.forEach { seg ->
                                            when (seg) {
                                                is VmPathSegment.MoveTo -> androidPath.moveTo(seg.x.safeFinite(), seg.y.safeFinite())
                                                is VmPathSegment.LineTo -> androidPath.lineTo(seg.x.safeFinite(), seg.y.safeFinite())
                                                is VmPathSegment.QuadTo -> androidPath.quadTo(seg.cx.safeFinite(), seg.cy.safeFinite(), seg.x.safeFinite(), seg.y.safeFinite())
                                                VmPathSegment.Close -> androidPath.close()
                                            }
                                        }
                                        paint.color = op.argb
                                        paint.style = if (op.isStroke) Paint.Style.STROKE else Paint.Style.FILL
                                        paint.strokeWidth = op.strokeWidth.safeFinite(2f).coerceIn(0.5f, 128f)
                                        paint.strokeCap = Paint.Cap.ROUND
                                        paint.strokeJoin = Paint.Join.ROUND
                                        native.drawPath(androidPath, paint)
                                    }
                                }
                                is VmCanvasOp.DrawText -> {
                                    textPaint.color = op.argb
                                    textPaint.textSize = op.textSizePx.safeFinite(24f).coerceIn(8f, 200f)
                                    textPaint.typeface = if (op.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                    textPaint.textAlign = when (op.align) {
                                        "CENTER" -> Paint.Align.CENTER
                                        "RIGHT" -> Paint.Align.RIGHT
                                        else -> Paint.Align.LEFT
                                    }
                                    native.drawText(op.text, op.x.safeFinite(), op.y.safeFinite(), textPaint)
                                }
                                VmCanvasOp.Save -> {
                                    if (native.saveCount < initialSave + 32) native.save()
                                }
                                VmCanvasOp.Restore -> {
                                    if (native.saveCount > initialSave) native.restore()
                                }
                                is VmCanvasOp.Translate -> native.translate(op.dx.safeFinite(), op.dy.safeFinite())
                                is VmCanvasOp.Rotate -> native.rotate(op.degrees.safeFinite(), op.px.safeFinite(), op.py.safeFinite())
                                is VmCanvasOp.Scale -> {
                                    val sx = op.sx.safeFinite(1f).coerceIn(-100f, 100f)
                                    val sy = op.sy.safeFinite(1f).coerceIn(-100f, 100f)
                                    native.scale(sx, sy, op.px.safeFinite(), op.py.safeFinite())
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // Never allow malformed custom Canvas commands to crash the host UI
                    } finally {
                        runCatching {
                            if (native.saveCount >= initialSave) {
                                native.restoreToCount(initialSave)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Float.safeFinite(fallback: Float = 0f): Float = if (isFinite()) this else fallback

@Composable
private fun RenderEmulatorNode(
    node: EmulatorNode,
    state: MutableMap<String, String>,
    strokes: MutableList<DrawnStroke>,
    activeStrokePoints: MutableList<Offset>,
    onAction: (EmulatorActionKind, String) -> Unit,
    modifier: Modifier = Modifier,
    insideVerticalScroll: Boolean = false,
) {
    when (node) {
        is EmulatorNode.ColumnContainer -> {
            val hasCanvasChild = node.children.any { it is EmulatorNode.DrawingCanvasWidget }
            val useScroll = !insideVerticalScroll && !hasCanvasChild
            val childInsideScroll = insideVerticalScroll || useScroll
            val colModifier = modifier
                .fillMaxSize()
                .background(node.backgroundColor ?: Color(0xFF0F172A))
                .let { if (useScroll) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = node.horizontalPaddingDp.coerceIn(0, 64).dp, vertical = 14.dp)

            Column(
                modifier = colModifier,
                verticalArrangement = if (node.centered && !hasCanvasChild) Arrangement.spacedBy(10.dp, Alignment.CenterVertically) else Arrangement.spacedBy(10.dp),
                horizontalAlignment = if (node.centered) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                node.children.forEach { child ->
                    val childModifier = when {
                        child is EmulatorNode.DrawingCanvasWidget && !childInsideScroll -> Modifier.weight(1f).fillMaxWidth()
                        child is EmulatorNode.DrawingCanvasWidget -> Modifier.fillMaxWidth().height(240.dp)
                        else -> Modifier
                    }
                    RenderEmulatorNode(
                        node = child,
                        state = state,
                        strokes = strokes,
                        activeStrokePoints = activeStrokePoints,
                        onAction = onAction,
                        modifier = childModifier,
                        insideVerticalScroll = childInsideScroll,
                    )
                }
            }
        }

        is EmulatorNode.RowContainer -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(node.backgroundColor ?: Color.Transparent)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                horizontalArrangement = if (node.centered) Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                node.children.forEach { child ->
                    val w = (child as? EmulatorNode.ButtonWidget)?.weight ?: 0f
                    RenderEmulatorNode(
                        node = child,
                        state = state,
                        strokes = strokes,
                        activeStrokePoints = activeStrokePoints,
                        onAction = onAction,
                        modifier = if (w.isFinite() && w > 0f && w < 100f) Modifier.weight(w) else Modifier,
                        insideVerticalScroll = insideVerticalScroll,
                    )
                }
            }
        }

        is EmulatorNode.GridContainer -> {
            val cols = node.columns.coerceAtLeast(1)
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.children.chunked(cols).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { cell ->
                            RenderEmulatorNode(
                                node = cell,
                                state = state,
                                strokes = strokes,
                                activeStrokePoints = activeStrokePoints,
                                onAction = onAction,
                                modifier = Modifier.weight(1f),
                                insideVerticalScroll = insideVerticalScroll,
                            )
                        }
                    }
                }
            }
        }

        is EmulatorNode.TextWidget -> {
            val resolvedText = node.bindStateKey?.let { state[it] } ?: node.text
            val shape = RoundedCornerShape(node.cornerRadiusDp.safeFinite(0f).coerceIn(0f, 32f).dp)
            Box(
                modifier = modifier
                    .let {
                        if (node.backgroundColor != null) it.background(node.backgroundColor, shape) else it
                    }
                    .let {
                        if (node.borderColor != null) it.border(1.dp, node.borderColor, shape) else it
                    }
                    .padding(horizontal = if (node.backgroundColor != null) 18.dp else 4.dp, vertical = if (node.backgroundColor != null) 12.dp else 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = resolvedText,
                    fontSize = (node.textSizeSp * 0.78f).safeFinite(14f).coerceIn(8f, 52f).sp,
                    color = node.textColor ?: Color.White,
                    fontWeight = if (node.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = if (node.isMonospace) FontFamily.Monospace else FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is EmulatorNode.ButtonWidget -> {
            Button(
                onClick = { onAction(node.actionKind, node.actionArg) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = node.backgroundColor ?: Color(0xFF0284C7),
                    contentColor = node.textColor ?: Color.White,
                ),
                shape = RoundedCornerShape(node.cornerRadiusDp.safeFinite(8f).coerceIn(0f, 24f).dp),
                modifier = modifier,
            ) {
                Text(
                    text = node.label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is EmulatorNode.InputWidget -> {
            val value = state[node.stateKey].orEmpty()
            OutlinedTextField(
                value = value,
                onValueChange = { state[node.stateKey] = it },
                placeholder = { Text(node.hint, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is EmulatorNode.NoteListWidget -> {
            val raw = state[node.stateKey].orEmpty()
            val lines = raw.split('\n').filter { it.isNotBlank() }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                lines.take(8).forEach { entry ->
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = entry,
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }

        is EmulatorNode.DrawingCanvasWidget -> {
            val activeBrush = EmulatorEngine.parseColorSafe(state["brushColor"] ?: "#38BDF8") ?: node.defaultColor
            Canvas(
                modifier = modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF0B0F17))
                    .pointerInput(activeBrush) {
                        detectDragGestures(
                            onDragStart = { start ->
                                activeStrokePoints.clear()
                                if (start.x.isFinite() && start.y.isFinite()) {
                                    activeStrokePoints.add(start)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val pt = change.position
                                if (pt.x.isFinite() && pt.y.isFinite()) {
                                    activeStrokePoints.add(pt)
                                }
                            },
                            onDragEnd = {
                                if (activeStrokePoints.isNotEmpty()) {
                                    strokes.add(DrawnStroke(activeBrush, activeStrokePoints.toList()))
                                    activeStrokePoints.clear()
                                }
                            },
                        )
                    },
            ) {
                fun drawPts(pts: List<Offset>, color: Color) {
                    val valid = pts.filter { it.x.isFinite() && it.y.isFinite() }
                    if (valid.size < 2) return
                    val path = Path().apply {
                        moveTo(valid.first().x, valid.first().y)
                        valid.drop(1).forEach { p -> lineTo(p.x, p.y) }
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                strokes.forEach { s -> drawPts(s.points, s.color) }
                drawPts(activeStrokePoints, activeBrush)
            }
        }
    }
}
