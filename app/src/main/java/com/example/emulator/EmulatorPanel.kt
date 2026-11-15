package com.example.emulator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProjectFile
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

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
    var dialogMessage by remember { mutableStateOf<String?>(null) }

    val program = remember(appName, packageName, files, restartNonce) {
        EmulatorEngine.parseProject(appName, packageName, files)
    }

    val runtimeState = remember(program, restartNonce) {
        mutableStateMapOf<String, String>().apply {
            putAll(program.initialState)
        }
    }
    val strokes = remember(restartNonce) { mutableStateListOf<DrawnStroke>() }
    val activeStrokePoints = remember(restartNonce) { mutableStateListOf<Offset>() }
    val logcat = remember(program.packageName, restartNonce) {
        mutableStateListOf(
            "I/ActivityTaskManager: START u0 {cmp=${program.packageName}/.MainActivity}",
            "D/DroidForgeVM: Parsed ${program.detectedWidgetsCount} widget(s) from ${files.size} project file(s)",
            "I/MainActivity: onCreate() completed",
        )
    }

    fun appendLog(line: String) {
        logcat.add(line)
        if (logcat.size > 60) logcat.removeAt(0)
    }

    // Live timer coroutine when PulseTimer template is running
    val timerRunning = runtimeState["timerRunning"] == "true"
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

    fun handleAction(kind: EmulatorActionKind, arg: String) {
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
                dialogMessage = arg
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
                    Text("Live Android Emulator", style = MaterialTheme.typography.titleMedium)
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
                    Icon(Icons.Default.Refresh, contentDescription = "Restart activity")
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
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                if (!builtApkPath.isNullOrBlank() && builtApkPath.endsWith(".apk")) {
                    FilterChip(
                        selected = false,
                        onClick = { onInstallApk(builtApkPath) },
                        label = { Text("Install APK", style = MaterialTheme.typography.labelSmall) },
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
                    profile.aspectHeight / profile.aspectWidth
                } else {
                    profile.aspectWidth / profile.aspectHeight
                }
                val frameModifier = if (isLandscape) {
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .aspectRatio(ratio.coerceAtMost(2.1f))
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(ratio.coerceIn(0.45f, 0.85f))
                }

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
                                    text = program.activityTitle,
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
                                    .fillMaxWidth(),
                            ) {
                                RenderEmulatorNode(
                                    node = program.root,
                                    state = runtimeState,
                                    strokes = strokes,
                                    activeStrokePoints = activeStrokePoints,
                                    onAction = ::handleAction,
                                )

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

    dialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialogMessage = null },
            title = { Text(program.appName) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { dialogMessage = null }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun RenderEmulatorNode(
    node: EmulatorNode,
    state: MutableMap<String, String>,
    strokes: MutableList<DrawnStroke>,
    activeStrokePoints: MutableList<Offset>,
    onAction: (EmulatorActionKind, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is EmulatorNode.ColumnContainer -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(node.backgroundColor ?: Color(0xFF0F172A))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = node.horizontalPaddingDp.dp, vertical = 14.dp),
                verticalArrangement = if (node.centered) Arrangement.spacedBy(10.dp, Alignment.CenterVertically) else Arrangement.spacedBy(10.dp),
                horizontalAlignment = if (node.centered) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                node.children.forEach { child ->
                    val childModifier = if (child is EmulatorNode.DrawingCanvasWidget) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier
                    }
                    RenderEmulatorNode(
                        node = child,
                        state = state,
                        strokes = strokes,
                        activeStrokePoints = activeStrokePoints,
                        onAction = onAction,
                        modifier = childModifier,
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
                        modifier = if (w > 0f) Modifier.weight(w) else Modifier,
                    )
                }
            }
        }

        is EmulatorNode.GridContainer -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.children.chunked(node.columns).forEach { rowItems ->
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
                            )
                        }
                    }
                }
            }
        }

        is EmulatorNode.TextWidget -> {
            val resolvedText = node.bindStateKey?.let { state[it] } ?: node.text
            val shape = RoundedCornerShape(node.cornerRadiusDp.dp)
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
                    fontSize = (node.textSizeSp * 0.78f).sp,
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
                shape = RoundedCornerShape(node.cornerRadiusDp.dp),
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
                                activeStrokePoints.add(start)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                activeStrokePoints.add(change.position)
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
                    if (pts.size < 2) return
                    val path = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        pts.drop(1).forEach { p -> lineTo(p.x, p.y) }
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
