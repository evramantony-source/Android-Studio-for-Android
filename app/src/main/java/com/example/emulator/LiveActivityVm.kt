package com.example.emulator

import androidx.compose.ui.graphics.Color
import com.example.compiler.KotlinToJavaTranspiler
import com.example.compiler.ModernJavaTranspiler
import com.example.model.ProjectFile
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// --- Live Framebuffer Draw Commands emitted by custom View.onDraw(Canvas) ---

sealed interface VmCanvasOp {
    data class FillColor(val argb: Int) : VmCanvasOp
    data class DrawRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        val argb: Int,
        val isStroke: Boolean,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawCircle(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val argb: Int,
        val isStroke: Boolean,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawOval(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val argb: Int,
        val isStroke: Boolean,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawLine(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val argb: Int,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawArc(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val useCenter: Boolean,
        val argb: Int,
        val isStroke: Boolean,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawPath(
        val segments: List<VmPathSegment>,
        val argb: Int,
        val isStroke: Boolean,
        val strokeWidth: Float,
    ) : VmCanvasOp

    data class DrawText(
        val text: String,
        val x: Float,
        val y: Float,
        val textSizePx: Float,
        val argb: Int,
        val align: String, // LEFT, CENTER, RIGHT
        val isBold: Boolean,
    ) : VmCanvasOp

    data object Save : VmCanvasOp
    data object Restore : VmCanvasOp
    data class Translate(val dx: Float, val dy: Float) : VmCanvasOp
    data class Rotate(val degrees: Float, val px: Float, val py: Float) : VmCanvasOp
    data class Scale(val sx: Float, val sy: Float, val px: Float, val py: Float) : VmCanvasOp
}

sealed interface VmPathSegment {
    data class MoveTo(val x: Float, val y: Float) : VmPathSegment
    data class LineTo(val x: Float, val y: Float) : VmPathSegment
    data class QuadTo(val cx: Float, val cy: Float, val x: Float, val y: Float) : VmPathSegment
    data object Close : VmPathSegment
}

// --- Live View Tree Snapshots rendered by Compose ---

sealed interface VmViewNode {
    val id: String
    val idName: String
    val backgroundColor: Int?
    val cornerRadiusPx: Float
    val paddingLeft: Int
    val paddingTop: Int
    val paddingRight: Int
    val paddingBottom: Int
    val layoutWeight: Float
    val visibility: Int // 0 = VISIBLE, 4 = INVISIBLE, 8 = GONE

    data class Container(
        override val id: String,
        override val idName: String,
        val kind: String, // LinearLayout, FrameLayout, ScrollView, GridLayout, RelativeLayout
        val orientationVertical: Boolean,
        val gravity: Int,
        val columns: Int,
        override val backgroundColor: Int?,
        override val cornerRadiusPx: Float,
        override val paddingLeft: Int,
        override val paddingTop: Int,
        override val paddingRight: Int,
        override val paddingBottom: Int,
        override val layoutWeight: Float,
        override val visibility: Int,
        val children: List<VmViewNode>,
    ) : VmViewNode

    data class Text(
        override val id: String,
        override val idName: String,
        val text: String,
        val textSizeSp: Float,
        val textColor: Int?,
        val isBold: Boolean,
        val isMonospace: Boolean,
        val gravity: Int,
        override val backgroundColor: Int?,
        override val cornerRadiusPx: Float,
        override val paddingLeft: Int,
        override val paddingTop: Int,
        override val paddingRight: Int,
        override val paddingBottom: Int,
        override val layoutWeight: Float,
        override val visibility: Int,
    ) : VmViewNode

    data class ButtonView(
        override val id: String,
        override val idName: String,
        val text: String,
        val textSizeSp: Float,
        val textColor: Int?,
        override val backgroundColor: Int?,
        override val cornerRadiusPx: Float,
        override val paddingLeft: Int,
        override val paddingTop: Int,
        override val paddingRight: Int,
        override val paddingBottom: Int,
        override val layoutWeight: Float,
        override val visibility: Int,
    ) : VmViewNode

    data class EditTextView(
        override val id: String,
        override val idName: String,
        val text: String,
        val hint: String,
        val textSizeSp: Float,
        val textColor: Int?,
        override val backgroundColor: Int?,
        override val cornerRadiusPx: Float,
        override val paddingLeft: Int,
        override val paddingTop: Int,
        override val paddingRight: Int,
        override val paddingBottom: Int,
        override val layoutWeight: Float,
        override val visibility: Int,
    ) : VmViewNode

    data class CustomCanvasView(
        override val id: String,
        override val idName: String,
        val className: String,
        val ops: List<VmCanvasOp>,
        override val backgroundColor: Int?,
        override val cornerRadiusPx: Float,
        override val paddingLeft: Int,
        override val paddingTop: Int,
        override val paddingRight: Int,
        override val paddingBottom: Int,
        override val layoutWeight: Float,
        override val visibility: Int,
    ) : VmViewNode
}

// --- Live VM Runtime Session consumed by EmulatorPanel ---

class LiveVmSession internal constructor(
    val appName: String,
    val packageName: String,
    val strings: Map<String, String>,
    val colors: Map<String, Int>,
    val layouts: Map<String, String>,
    private val classes: Map<String, VmClass>,
    private val mainClassName: String,
) {
    private var rootViewObj: VmObject? = null
    private var mainActivityObj: VmObject? = null
    private val viewsById = linkedMapOf<Int, VmObject>()
    private val viewNodeIds = linkedMapOf<String, VmObject>()
    private val pendingTimers = mutableListOf<ScheduledTask>()
    private var nextTaskId = 1L
    private var stepBudget = 0
    private val maxStepsPerCall = 120_000
    private var callDepth = 0
    private val maxCallDepth = 64
    private val stateLock = Any()

    var canvasWidthPx: Int = 900
        private set
    var canvasHeightPx: Int = 1500
        private set

    var lastRenderOpsCount: Int = 0
        private set

    val logs = mutableListOf<String>()
    val toastQueue = mutableListOf<String>()
    var activeDialog: Pair<String, String>? = null
        private set
    var activeDialogPositive: (() -> Unit)? = null
        private set
    var activeDialogNegative: (() -> Unit)? = null
        private set

    private data class ScheduledTask(
        val id: Long,
        val runnable: Any?,
        val triggerAtMillis: Long,
    )

    private class ReturnSignal(val value: Any?) : RuntimeException()
    private class BreakSignal : RuntimeException()
    private class ContinueSignal : RuntimeException()

    fun start(): Boolean {
        synchronized(stateLock) {
            logs.clear()
            toastQueue.clear()
            pendingTimers.clear()
        }
        viewsById.clear()
        viewNodeIds.clear()
        activeDialog = null
        stepBudget = 0
        callDepth = 0

        val cls = classes[mainClassName] ?: return false
        return runCatching {
            val act = createInstance(cls, emptyList())
            mainActivityObj = act
            callMethod(act, "onCreate", listOf(null))
            true
        }.onFailure { err ->
            appendLog("E/DroidForgeVM: ${err.message ?: err.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    fun updateViewportSize(widthPx: Int, heightPx: Int): Boolean {
        val w = widthPx.coerceIn(240, 2400)
        val h = heightPx.coerceIn(240, 3200)
        if (w == canvasWidthPx && h == canvasHeightPx) return false
        canvasWidthPx = w
        canvasHeightPx = h
        stepBudget = 0
        callDepth = 0
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<VmObject, Boolean>())
        dispatchSizeChanged(rootViewObj, w, h, visited, depth = 0)
        return true
    }

    private fun dispatchSizeChanged(
        view: VmObject?,
        w: Int,
        h: Int,
        visited: MutableSet<VmObject> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap()),
        depth: Int = 0,
    ) {
        if (view == null || depth > 32 || !visited.add(view)) return
        if (view.classDecl != null && hasMethod(view.classDecl, "onSizeChanged")) {
            runSafely {
                callMethod(view, "onSizeChanged", listOf(w, h, 0, 0))
            }
        }
        val children = (view.nativeTag as? List<*>)?.filterIsInstance<VmObject>().orEmpty()
        children.forEach { dispatchSizeChanged(it, w, h, visited, depth + 1) }
    }

    fun tickTimers(nowMillis: Long): Boolean {
        val due = synchronized(stateLock) {
            if (pendingTimers.isEmpty()) return false
            val ready = pendingTimers.filter { it.triggerAtMillis <= nowMillis }
            if (ready.isEmpty()) return false
            pendingTimers.removeAll(ready.toSet())
            ready
        }
        stepBudget = 0
        callDepth = 0
        due.forEach { task ->
            runSafely {
                invokeCallback(task.runnable)
            }
        }
        return true
    }

    fun hasPendingTimers(): Boolean = synchronized(stateLock) { pendingTimers.isNotEmpty() }

    fun drainLogs(): List<String> = synchronized(stateLock) {
        if (logs.isEmpty()) emptyList() else logs.toList().also { logs.clear() }
    }

    fun drainLatestToast(): String? = synchronized(stateLock) {
        val latest = toastQueue.lastOrNull()
        toastQueue.clear()
        latest
    }

    fun buildSnapshot(): VmViewNode? {
        viewNodeIds.clear()
        lastRenderOpsCount = 0
        stepBudget = 0
        callDepth = 0
        val root = rootViewObj ?: return null
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<VmObject, Boolean>())
        return runCatching {
            snapshotView(root, "root", visited, depth = 0)
        }.onFailure { err ->
            appendLog("E/DroidForgeVM: Snapshot failed: ${err.message ?: err.javaClass.simpleName}")
        }.getOrNull()
    }

    fun dispatchClick(nodeId: String) {
        val view = viewNodeIds[nodeId] ?: return
        val listener = view.fields["__onClickListener"] ?: return
        stepBudget = 0
        callDepth = 0
        runSafely {
            invokeCallback(listener, listOf(view))
        }
    }

    fun dispatchEditTextChange(nodeId: String, newText: String) {
        val view = viewNodeIds[nodeId] ?: return
        view.fields["__text"] = newText
    }

    fun dispatchTouch(nodeId: String, action: Int, xPx: Float, yPx: Float) {
        val view = viewNodeIds[nodeId] ?: return
        val cls = view.classDecl ?: return
        if (!hasMethod(cls, "onTouchEvent")) return
        val safeX = if (xPx.isFinite()) xPx else 0f
        val safeY = if (yPx.isFinite()) yPx else 0f
        val event = VmObject("MotionEvent", null).apply {
            fields["__action"] = action
            fields["__x"] = safeX
            fields["__y"] = safeY
        }
        stepBudget = 0
        callDepth = 0
        runSafely {
            callMethod(view, "onTouchEvent", listOf(event))
        }
    }

    fun dismissDialog(positive: Boolean?) {
        val cb = if (positive == true) activeDialogPositive else activeDialogNegative
        activeDialog = null
        activeDialogPositive = null
        activeDialogNegative = null
        if (cb != null) {
            stepBudget = 0
            callDepth = 0
            runSafely { cb() }
        }
    }

    private fun runSafely(block: () -> Unit) {
        runCatching(block).onFailure { err ->
            if (err !is ReturnSignal) {
                appendLog("W/DroidForgeVM: ${err.message ?: err.javaClass.simpleName}")
            }
        }
    }

    private fun appendLog(line: String) {
        synchronized(stateLock) {
            logs.add(line)
            while (logs.size > 120) logs.removeAt(0)
        }
    }

    private fun snapshotView(
        view: VmObject,
        pathId: String,
        visited: MutableSet<VmObject>,
        depth: Int,
    ): VmViewNode {
        viewNodeIds[pathId] = view
        val kind = view.typeName
        val cls = view.classDecl
        val hasCustomOnDraw = cls != null && (hasMethod(cls, "onDraw") || hasMethod(cls, "draw"))
        @Suppress("UNCHECKED_CAST")
        val surfaceOps = view.fields["__surfaceOps"] as? List<VmCanvasOp>

        val bgDrawable = view.fields["__backgroundDrawable"] as? VmObject
        val bgColor = (view.fields["__backgroundColor"] as? Number)?.toInt()
            ?: (bgDrawable?.fields?.get("__color") as? Number)?.toInt()
        val cornerRadius = (bgDrawable?.fields?.get("__cornerRadius") as? Number)?.toFloat() ?: 0f
        val padL = (view.fields["__padL"] as? Number)?.toInt() ?: 0
        val padT = (view.fields["__padT"] as? Number)?.toInt() ?: 0
        val padR = (view.fields["__padR"] as? Number)?.toInt() ?: 0
        val padB = (view.fields["__padB"] as? Number)?.toInt() ?: 0
        val weight = (view.fields["__layoutWeight"] as? Number)?.toFloat() ?: 0f
        val visibility = (view.fields["__visibility"] as? Number)?.toInt() ?: 0
        val idName = view.fields["__idName"] as? String ?: ""

        if (hasCustomOnDraw || surfaceOps != null) {
            val ops = if (cls != null && hasCustomOnDraw) {
                val canvasObj = VmObject("Canvas", null)
                val drawn = mutableListOf<VmCanvasOp>()
                canvasObj.nativeTag = drawn
                val drawMethod = if (hasMethod(cls, "onDraw")) "onDraw" else "draw"
                runSafely {
                    callMethod(view, drawMethod, listOf(canvasObj))
                }
                drawn
            } else {
                surfaceOps.orEmpty()
            }
            lastRenderOpsCount += ops.size
            return VmViewNode.CustomCanvasView(
                id = pathId,
                idName = idName,
                className = kind,
                ops = ops,
                backgroundColor = bgColor,
                cornerRadiusPx = cornerRadius,
                paddingLeft = padL,
                paddingTop = padT,
                paddingRight = padR,
                paddingBottom = padB,
                layoutWeight = weight,
                visibility = visibility,
            )
        }

        val nativeKind = resolveNativeWidgetType(kind, cls)
        return when (nativeKind) {
            "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout" -> {
                val canRecurse = depth < 32 && visited.add(view)
                val rawChildren = if (canRecurse) {
                    (view.nativeTag as? List<*>)?.filterIsInstance<VmObject>().orEmpty()
                } else {
                    emptyList()
                }
                val childNodes = rawChildren.mapIndexedNotNull { idx, child ->
                    if (child in visited) null else snapshotView(child, "${pathId}_$idx", visited, depth + 1)
                }
                val vertical = (view.fields["__orientation"] as? Number)?.toInt() != 0
                val gravity = (view.fields["__gravity"] as? Number)?.toInt() ?: 0
                val cols = (view.fields["__columnCount"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 4
                VmViewNode.Container(
                    id = pathId,
                    idName = idName,
                    kind = nativeKind,
                    orientationVertical = vertical,
                    gravity = gravity,
                    columns = cols,
                    backgroundColor = bgColor,
                    cornerRadiusPx = cornerRadius,
                    paddingLeft = padL,
                    paddingTop = padT,
                    paddingRight = padR,
                    paddingBottom = padB,
                    layoutWeight = weight,
                    visibility = visibility,
                    children = childNodes,
                )
            }
            "Button" -> VmViewNode.ButtonView(
                id = pathId,
                idName = idName,
                text = view.fields["__text"]?.toString().orEmpty(),
                textSizeSp = (view.fields["__textSize"] as? Number)?.toFloat() ?: 14f,
                textColor = (view.fields["__textColor"] as? Number)?.toInt(),
                backgroundColor = bgColor,
                cornerRadiusPx = cornerRadius.takeIf { it > 0f } ?: 16f,
                paddingLeft = padL,
                paddingTop = padT,
                paddingRight = padR,
                paddingBottom = padB,
                layoutWeight = weight,
                visibility = visibility,
            )
            "EditText" -> VmViewNode.EditTextView(
                id = pathId,
                idName = idName,
                text = view.fields["__text"]?.toString().orEmpty(),
                hint = view.fields["__hint"]?.toString().orEmpty(),
                textSizeSp = (view.fields["__textSize"] as? Number)?.toFloat() ?: 14f,
                textColor = (view.fields["__textColor"] as? Number)?.toInt(),
                backgroundColor = bgColor,
                cornerRadiusPx = cornerRadius.takeIf { it > 0f } ?: 12f,
                paddingLeft = padL,
                paddingTop = padT,
                paddingRight = padR,
                paddingBottom = padB,
                layoutWeight = weight,
                visibility = visibility,
            )
            else -> VmViewNode.Text(
                id = pathId,
                idName = idName,
                text = view.fields["__text"]?.toString().orEmpty(),
                textSizeSp = (view.fields["__textSize"] as? Number)?.toFloat() ?: 14f,
                textColor = (view.fields["__textColor"] as? Number)?.toInt(),
                isBold = view.fields["__isBold"] == true,
                isMonospace = view.fields["__isMono"] == true,
                gravity = (view.fields["__gravity"] as? Number)?.toInt() ?: 0,
                backgroundColor = bgColor,
                cornerRadiusPx = cornerRadius,
                paddingLeft = padL,
                paddingTop = padT,
                paddingRight = padR,
                paddingBottom = padB,
                layoutWeight = weight,
                visibility = visibility,
            )
        }
    }

    private fun resolveNativeWidgetType(
        typeName: String,
        cls: VmClass?,
        visited: MutableSet<String> = mutableSetOf(),
    ): String {
        val simple = typeName.substringAfterLast('.')
        if (!visited.add(simple)) return "View"
        return when (simple) {
            "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout",
            "Button", "EditText", "TextView", "SurfaceView", "View" -> if (simple == "SurfaceView") "View" else simple
            else -> {
                val superName = cls?.superName?.substringAfterLast('.') ?: "View"
                if (superName != simple) resolveNativeWidgetType(superName, classes[superName], visited) else "View"
            }
        }
    }

    private fun hasMethod(
        cls: VmClass,
        name: String,
        visited: MutableSet<String> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(cls.name)) return false
        if (cls.methods.any { it.name == name }) return true
        val sup = cls.superName?.let { classes[it] } ?: return false
        return hasMethod(sup, name, visited)
    }

    private fun findMethod(
        cls: VmClass,
        name: String,
        argCount: Int,
        visited: MutableSet<String> = mutableSetOf(),
    ): VmMethod? {
        if (!visited.add(cls.name)) return null
        val candidates = cls.methods.filter { it.name == name }
        val exact = candidates.firstOrNull { it.params.size == argCount }
        if (exact != null) return exact
        if (candidates.isNotEmpty()) return candidates.first()
        val sup = cls.superName?.let { classes[it] } ?: return null
        return findMethod(sup, name, argCount, visited)
    }

    private fun createInstance(
        cls: VmClass,
        args: List<Any?>,
        outerThis: VmObject? = null,
        outerScope: VmScope? = null,
    ): VmObject {
        val obj = VmObject(cls.name, cls).apply {
            this.outerThis = outerThis
            this.outerScope = outerScope
        }
        if (resolveNativeWidgetType(cls.name, cls) in setOf("LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout")) {
            obj.nativeTag = mutableListOf<VmObject>()
        }
        if (callDepth >= maxCallDepth) return obj
        callDepth++
        try {
            initializeFields(obj, cls, mutableSetOf())
            val ctors = cls.constructors
            val ctor = ctors.firstOrNull { it.params.size == args.size } ?: ctors.firstOrNull()
            if (ctor != null) {
                val env = VmScope(outerScope)
                env.define("this", obj)
                ctor.params.forEachIndexed { i, p -> env.define(p.second, args.getOrNull(i)) }
                try {
                    execBlock(ctor.body, env, obj, cls)
                } catch (_: ReturnSignal) {
                }
            }
        } finally {
            callDepth--
        }
        return obj
    }

    private fun initializeFields(
        obj: VmObject,
        cls: VmClass,
        visited: MutableSet<String> = mutableSetOf(),
    ) {
        if (!visited.add(cls.name)) return
        cls.superName?.let { classes[it] }?.let { sup -> initializeFields(obj, sup, visited) }
        val env = VmScope(null).apply { define("this", obj) }
        cls.fields.forEach { f ->
            val value = if (f.initExpr != null) {
                evalExpr(f.initExpr, env, obj, cls)
            } else {
                defaultValueForType(f.type)
            }
            obj.fields[f.name] = value
        }
    }

    private fun defaultValueForType(type: String): Any? = when (type) {
        "int", "short", "byte" -> 0
        "long" -> 0L
        "float" -> 0f
        "double" -> 0.0
        "boolean" -> false
        "char" -> '\u0000'
        else -> null
    }

    private fun invokeCallback(target: Any?, args: List<Any?> = emptyList()) {
        if (callDepth >= maxCallDepth) return
        callDepth++
        try {
            when (target) {
                is VmLambda -> {
                    val env = VmScope(target.closure)
                    target.params.forEachIndexed { idx, pName -> env.define(pName, args.getOrNull(idx)) }
                    try {
                        execStmt(target.body, env, target.thisObj, target.ownerClass)
                    } catch (_: ReturnSignal) {
                    }
                }
                is VmObject -> {
                    val cls = target.classDecl ?: return
                    val m = cls.methods.firstOrNull { it.name == "onClick" || it.name == "run" } ?: return
                    callMethod(target, m.name, args)
                }
            }
        } finally {
            callDepth--
        }
    }

    private fun callMethod(receiver: VmObject, name: String, args: List<Any?>): Any? {
        if (callDepth >= maxCallDepth) return null
        val cls = receiver.classDecl ?: return null
        val m = findMethod(cls, name, args.size) ?: return null
        val env = VmScope(receiver.outerScope)
        env.define("this", receiver)
        m.params.forEachIndexed { idx, (_, pName) ->
            env.define(pName, args.getOrNull(idx))
        }
        callDepth++
        return try {
            execBlock(m.body, env, receiver, cls)
            null
        } catch (r: ReturnSignal) {
            r.value
        } finally {
            callDepth--
        }
    }

    private fun callStaticMethod(cls: VmClass, name: String, args: List<Any?>): Any? {
        if (callDepth >= maxCallDepth) return null
        val m = findMethod(cls, name, args.size) ?: return null
        val env = VmScope(null)
        m.params.forEachIndexed { idx, (_, pName) -> env.define(pName, args.getOrNull(idx)) }
        callDepth++
        return try {
            execBlock(m.body, env, null, cls)
            null
        } catch (r: ReturnSignal) {
            r.value
        } finally {
            callDepth--
        }
    }

    private fun execBlock(stmts: List<VmStmt>, env: VmScope, thisObj: VmObject?, cls: VmClass?) {
        val child = VmScope(env)
        for (stmt in stmts) {
            if (++stepBudget > maxStepsPerCall) return
            execStmt(stmt, child, thisObj, cls)
        }
    }

    private fun execStmt(stmt: VmStmt, env: VmScope, thisObj: VmObject?, cls: VmClass?) {
        if (++stepBudget > maxStepsPerCall) return
        when (stmt) {
            is VmStmt.Block -> execBlock(stmt.stmts, env, thisObj, cls)
            is VmStmt.ExprStmt -> evalExpr(stmt.expr, env, thisObj, cls)
            is VmStmt.VarDecl -> {
                stmt.decls.forEach { (name, init) ->
                    val valOrDefault = if (init != null) {
                        evalExpr(init, env, thisObj, cls)
                    } else {
                        defaultValueForType(stmt.type)
                    }
                    env.define(name, valOrDefault)
                }
            }
            is VmStmt.Return -> {
                val v = stmt.expr?.let { evalExpr(it, env, thisObj, cls) }
                throw ReturnSignal(v)
            }
            is VmStmt.Break -> throw BreakSignal()
            is VmStmt.Continue -> throw ContinueSignal()
            is VmStmt.If -> {
                val cond = toBoolean(evalExpr(stmt.cond, env, thisObj, cls))
                if (cond) {
                    execStmt(stmt.thenBranch, env, thisObj, cls)
                } else {
                    stmt.elseBranch?.let { execStmt(it, env, thisObj, cls) }
                }
            }
            is VmStmt.While -> {
                var guard = 0
                while (guard++ < 10_000 && toBoolean(evalExpr(stmt.cond, env, thisObj, cls))) {
                    try {
                        execStmt(stmt.body, env, thisObj, cls)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        continue
                    }
                }
            }
            is VmStmt.DoWhile -> {
                var guard = 0
                do {
                    try {
                        execStmt(stmt.body, env, thisObj, cls)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        // continue to condition check
                    }
                } while (guard++ < 10_000 && toBoolean(evalExpr(stmt.cond, env, thisObj, cls)))
            }
            is VmStmt.For -> {
                val loopScope = VmScope(env)
                stmt.init?.let { execStmt(it, loopScope, thisObj, cls) }
                var guard = 0
                while (guard++ < 10_000 && (stmt.cond == null || toBoolean(evalExpr(stmt.cond, loopScope, thisObj, cls)))) {
                    try {
                        execStmt(stmt.body, loopScope, thisObj, cls)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        // fall through to update
                    }
                    stmt.update.forEach { evalExpr(it, loopScope, thisObj, cls) }
                }
            }
            is VmStmt.ForEach -> {
                val iterableVal = evalExpr(stmt.iterable, env, thisObj, cls)
                val items: List<Any?> = when (iterableVal) {
                    is List<*> -> iterableVal.toList()
                    is Set<*> -> iterableVal.toList()
                    is Array<*> -> iterableVal.toList()
                    is IntArray -> iterableVal.toList()
                    is FloatArray -> iterableVal.toList()
                    is DoubleArray -> iterableVal.toList()
                    is BooleanArray -> iterableVal.toList()
                    else -> emptyList()
                }
                for (item in items) {
                    val loopScope = VmScope(env)
                    loopScope.define(stmt.varName, item)
                    try {
                        execStmt(stmt.body, loopScope, thisObj, cls)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        continue
                    }
                }
            }
            is VmStmt.Switch -> {
                val target = evalExpr(stmt.expr, env, thisObj, cls)
                var matched = false
                try {
                    for (arm in stmt.cases) {
                        if (!matched) {
                            matched = arm.caseExprs.isEmpty() || arm.caseExprs.any { ce ->
                                valuesEqual(target, evalExpr(ce, env, thisObj, cls))
                            }
                        }
                        if (matched) {
                            for (s in arm.stmts) {
                                execStmt(s, env, thisObj, cls)
                            }
                        }
                    }
                } catch (_: BreakSignal) {
                }
            }
            is VmStmt.TryCatch -> {
                try {
                    execStmt(stmt.tryBlock, env, thisObj, cls)
                } catch (r: ReturnSignal) {
                    throw r
                } catch (b: BreakSignal) {
                    throw b
                } catch (c: ContinueSignal) {
                    throw c
                } catch (e: Throwable) {
                    stmt.catchBlock?.let { cb ->
                        val cEnv = VmScope(env)
                        stmt.catchVar?.let { cv -> cEnv.define(cv, e.message ?: "Error") }
                        execStmt(cb, cEnv, thisObj, cls)
                    }
                }
            }
            is VmStmt.Empty -> Unit
        }
    }

    private fun evalExpr(expr: VmExpr, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any? {
        return when (expr) {
            is VmExpr.Literal -> expr.value
            is VmExpr.This -> thisObj
            is VmExpr.Ident -> resolveIdent(expr.name, env, thisObj, cls)
            is VmExpr.Unary -> {
                val v = evalExpr(expr.expr, env, thisObj, cls)
                when (expr.op) {
                    "!" -> !toBoolean(v)
                    "-" -> negateNumber(v)
                    "+" -> v
                    "~" -> (toInt(v)).inv()
                    "++" -> applyIncDec(expr.expr, env, thisObj, cls, +1, returnOld = false)
                    "--" -> applyIncDec(expr.expr, env, thisObj, cls, -1, returnOld = false)
                    else -> v
                }
            }
            is VmExpr.Postfix -> {
                when (expr.op) {
                    "++" -> applyIncDec(expr.expr, env, thisObj, cls, +1, returnOld = true)
                    "--" -> applyIncDec(expr.expr, env, thisObj, cls, -1, returnOld = true)
                    else -> evalExpr(expr.expr, env, thisObj, cls)
                }
            }
            is VmExpr.Cast -> {
                val v = evalExpr(expr.expr, env, thisObj, cls)
                castValue(v, expr.targetType)
            }
            is VmExpr.InstanceOf -> {
                val v = evalExpr(expr.expr, env, thisObj, cls)
                v != null && (v is VmObject && (v.typeName == expr.typeName || isSubclassOf(v.classDecl, expr.typeName)))
            }
            is VmExpr.Ternary -> {
                if (toBoolean(evalExpr(expr.cond, env, thisObj, cls))) {
                    evalExpr(expr.thenExpr, env, thisObj, cls)
                } else {
                    evalExpr(expr.elseExpr, env, thisObj, cls)
                }
            }
            is VmExpr.Binary -> evalBinary(expr, env, thisObj, cls)
            is VmExpr.Assign -> {
                val rhs = evalExpr(expr.rhs, env, thisObj, cls)
                assignTarget(expr.lhs, expr.op, rhs, env, thisObj, cls)
            }
            is VmExpr.FieldAccess -> {
                if (expr.field == "this" && expr.target is VmExpr.Ident) {
                    val want = expr.target.name
                    var cur = thisObj
                    var guard = 0
                    while (cur != null && guard++ < 8) {
                        if (cur.typeName == want) return cur
                        cur = cur.outerThis
                    }
                    return thisObj ?: mainActivityObj
                }
                if (expr.target is VmExpr.Ident && expr.target.name !in env && (thisObj == null || !thisObj.fields.containsKey(expr.target.name))) {
                    val staticRes = resolveStaticField(expr.target.name, expr.field)
                    if (staticRes !== UNRESOLVED) return staticRes
                }
                val recv = evalExpr(expr.target, env, thisObj, cls)
                getFieldValue(recv, expr.field)
            }
            is VmExpr.IndexAccess -> {
                val arr = evalExpr(expr.target, env, thisObj, cls)
                val idx = toInt(evalExpr(expr.index, env, thisObj, cls))
                getIndexValue(arr, idx)
            }
            is VmExpr.Call -> evalCall(expr, env, thisObj, cls)
            is VmExpr.NewObject -> evalNewObject(expr, env, thisObj, cls)
            is VmExpr.NewArray -> evalNewArray(expr, env, thisObj, cls)
            is VmExpr.ArrayInit -> expr.elements.map { evalExpr(it, env, thisObj, cls) }.toTypedArray()
            is VmExpr.Lambda -> VmLambda(expr.params, expr.body, env, thisObj, cls)
        }
    }

    private fun isSubclassOf(decl: VmClass?, targetName: String): Boolean {
        val visited = mutableSetOf<String>()
        var cur = decl
        while (cur != null && visited.add(cur.name)) {
            if (cur.name == targetName) return true
            cur = cur.superName?.let { classes[it] }
        }
        return false
    }

    private fun resolveIdent(name: String, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any? {
        if (env.contains(name)) return env.get(name)
        var curObj = thisObj
        var guard = 0
        while (curObj != null && guard++ < 8) {
            if (curObj.fields.containsKey(name)) return curObj.fields[name]
            curObj.classDecl?.staticFields?.get(name)?.let { return it }
            curObj = curObj.outerThis
        }
        if (cls != null) {
            cls.staticFields[name]?.let { return it }
            if (name == "R") return VmNamespace("R")
        }
        return when (name) {
            "true" -> true
            "false" -> false
            "null" -> null
            "R", "Color", "Math", "Gravity", "View", "LinearLayout", "MotionEvent",
            "Toast", "Paint", "Typeface", "Log", "System", "Arrays", "Collections",
            "String", "Integer", "Float", "Double", "Boolean" -> VmNamespace(name)
            else -> VmNamespace(name)
        }
    }

    private fun resolveStaticField(targetName: String, field: String): Any? {
        if (targetName == "R") return VmNamespace("R.$field")
        if (targetName.startsWith("R.")) {
            val resKind = targetName.removePrefix("R.")
            return when (resKind) {
                "string" -> "__res_string_$field"
                "color" -> "__res_color_$field"
                "layout" -> "__res_layout_$field"
                "id" -> field.hashCode()
                else -> 0
            }
        }
        classes[targetName]?.let { c ->
            c.staticFields[field]?.let { return it }
        }
        return when (targetName) {
            "Color" -> when (field) {
                "BLACK" -> 0xFF000000.toInt()
                "WHITE" -> 0xFFFFFFFF.toInt()
                "RED" -> 0xFFFF0000.toInt()
                "GREEN" -> 0xFF00FF00.toInt()
                "BLUE" -> 0xFF0000FF.toInt()
                "CYAN" -> 0xFF00FFFF.toInt()
                "YELLOW" -> 0xFFFFFF00.toInt()
                "MAGENTA" -> 0xFFFF00FF.toInt()
                "DKGRAY" -> 0xFF444444.toInt()
                "GRAY" -> 0xFF888888.toInt()
                "LTGRAY" -> 0xFFCCCCCC.toInt()
                "TRANSPARENT" -> 0x00000000
                else -> 0xFF000000.toInt()
            }
            "Math" -> when (field) {
                "PI" -> PI
                "E" -> kotlin.math.E
                else -> 0.0
            }
            "Gravity" -> when (field) {
                "CENTER" -> 17
                "CENTER_HORIZONTAL" -> 1
                "CENTER_VERTICAL" -> 16
                "LEFT", "START" -> 3
                "RIGHT", "END" -> 5
                "TOP" -> 48
                "BOTTOM" -> 80
                else -> 0
            }
            "LinearLayout" -> when (field) {
                "HORIZONTAL" -> 0
                "VERTICAL" -> 1
                else -> 1
            }
            "LinearLayout.LayoutParams", "LayoutParams" -> when (field) {
                "MATCH_PARENT", "FILL_PARENT" -> -1
                "WRAP_CONTENT" -> -2
                else -> -2
            }
            "View" -> when (field) {
                "VISIBLE" -> 0
                "INVISIBLE" -> 4
                "GONE" -> 8
                else -> 0
            }
            "MotionEvent" -> when (field) {
                "ACTION_DOWN" -> 0
                "ACTION_UP" -> 1
                "ACTION_MOVE" -> 2
                "ACTION_CANCEL" -> 3
                "ACTION_MASK" -> 255
                else -> 0
            }
            "Toast" -> when (field) {
                "LENGTH_SHORT" -> 0
                "LENGTH_LONG" -> 1
                else -> 0
            }
            "Paint.Style", "Style" -> when (field) {
                "FILL" -> "FILL"
                "STROKE" -> "STROKE"
                "FILL_AND_STROKE" -> "FILL_AND_STROKE"
                else -> "FILL"
            }
            "Paint.Align", "Align" -> when (field) {
                "LEFT" -> "LEFT"
                "CENTER" -> "CENTER"
                "RIGHT" -> "RIGHT"
                else -> "LEFT"
            }
            "Typeface" -> when (field) {
                "DEFAULT" -> "DEFAULT"
                "DEFAULT_BOLD" -> "DEFAULT_BOLD"
                "MONOSPACE" -> "MONOSPACE"
                "SANS_SERIF" -> "SANS_SERIF"
                "BOLD" -> 1
                "ITALIC" -> 2
                "NORMAL" -> 0
                else -> "DEFAULT"
            }
            "Integer" -> when (field) {
                "MAX_VALUE" -> Int.MAX_VALUE
                "MIN_VALUE" -> Int.MIN_VALUE
                else -> 0
            }
            "Float" -> when (field) {
                "MAX_VALUE" -> Float.MAX_VALUE
                else -> 0f
            }
            else -> UNRESOLVED
        }
    }

    private fun getFieldValue(recv: Any?, field: String): Any? {
        if (recv is VmNamespace) {
            val resolved = resolveStaticField(recv.name, field)
            return if (resolved !== UNRESOLVED) resolved else VmNamespace("${recv.name}.$field")
        }
        if (recv is VmObject) {
            return recv.fields[field]
        }
        return when (field) {
            "length" -> when (recv) {
                is Array<*> -> recv.size
                is IntArray -> recv.size
                is FloatArray -> recv.size
                is DoubleArray -> recv.size
                is BooleanArray -> recv.size
                is String -> recv.length
                else -> 0
            }
            "left" -> (recv as? VmRect)?.left ?: 0f
            "top" -> (recv as? VmRect)?.top ?: 0f
            "right" -> (recv as? VmRect)?.right ?: 0f
            "bottom" -> (recv as? VmRect)?.bottom ?: 0f
            else -> null
        }
    }

    private fun getIndexValue(arr: Any?, idx: Int): Any? = when (arr) {
        is Array<*> -> arr.getOrNull(idx)
        is IntArray -> arr.getOrNull(idx) ?: 0
        is FloatArray -> arr.getOrNull(idx) ?: 0f
        is DoubleArray -> arr.getOrNull(idx) ?: 0.0
        is BooleanArray -> arr.getOrNull(idx) ?: false
        is List<*> -> arr.getOrNull(idx)
        else -> null
    }

    private fun setIndexValue(arr: Any?, idx: Int, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (arr) {
            is Array<*> -> if (idx in arr.indices) (arr as Array<Any?>)[idx] = value
            is IntArray -> if (idx in arr.indices) arr[idx] = toInt(value)
            is FloatArray -> if (idx in arr.indices) arr[idx] = toFloat(value)
            is DoubleArray -> if (idx in arr.indices) arr[idx] = toDouble(value)
            is BooleanArray -> if (idx in arr.indices) arr[idx] = toBoolean(value)
            is MutableList<*> -> {
                val m = arr as MutableList<Any?>
                if (idx in m.indices) m[idx] = value
            }
        }
    }

    private fun assignTarget(
        lhs: VmExpr,
        op: String,
        rhs: Any?,
        env: VmScope,
        thisObj: VmObject?,
        cls: VmClass?,
    ): Any? {
        val current = if (op == "=") null else evalExpr(lhs, env, thisObj, cls)
        val finalVal = when (op) {
            "=" -> rhs
            "+=" -> evalPlus(current, rhs)
            "-=" -> evalNumOp(current, rhs, "-")
            "*=" -> evalNumOp(current, rhs, "*")
            "/=" -> evalNumOp(current, rhs, "/")
            "%=" -> evalNumOp(current, rhs, "%")
            else -> rhs
        }
        when (lhs) {
            is VmExpr.Ident -> {
                if (env.contains(lhs.name)) {
                    env.set(lhs.name, finalVal)
                } else {
                    var targetObj: VmObject? = null
                    var cur = thisObj
                    var guard = 0
                    while (cur != null && guard++ < 8) {
                        if (cur.fields.containsKey(lhs.name) || cur.classDecl?.fields?.any { it.name == lhs.name } == true) {
                            targetObj = cur
                            break
                        }
                        cur = cur.outerThis
                    }
                    if (targetObj != null) {
                        targetObj.fields[lhs.name] = finalVal
                    } else {
                        env.define(lhs.name, finalVal)
                    }
                }
            }
            is VmExpr.FieldAccess -> {
                val recv = evalExpr(lhs.target, env, thisObj, cls)
                when (recv) {
                    is VmObject -> recv.fields[lhs.field] = finalVal
                    is VmRect -> when (lhs.field) {
                        "left" -> recv.left = toFloat(finalVal)
                        "top" -> recv.top = toFloat(finalVal)
                        "right" -> recv.right = toFloat(finalVal)
                        "bottom" -> recv.bottom = toFloat(finalVal)
                    }
                }
            }
            is VmExpr.IndexAccess -> {
                val arr = evalExpr(lhs.target, env, thisObj, cls)
                val idx = toInt(evalExpr(lhs.index, env, thisObj, cls))
                setIndexValue(arr, idx, finalVal)
            }
            else -> Unit
        }
        return finalVal
    }

    private fun applyIncDec(
        target: VmExpr,
        env: VmScope,
        thisObj: VmObject?,
        cls: VmClass?,
        delta: Int,
        returnOld: Boolean,
    ): Any? {
        val oldVal = evalExpr(target, env, thisObj, cls)
        val newVal: Any = when (oldVal) {
            is Float -> oldVal + delta
            is Double -> oldVal + delta
            is Long -> oldVal + delta
            else -> toInt(oldVal) + delta
        }
        assignTarget(target, "=", newVal, env, thisObj, cls)
        return if (returnOld) oldVal else newVal
    }

    private fun evalCall(expr: VmExpr.Call, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any? {
        // Super call?
        if (expr.target == null && expr.name == "super") return null
        val evalArgs = expr.args.map { evalExpr(it, env, thisObj, cls) }

        if (expr.target == null) {
            // Check Activity helper methods first, then custom methods
            when (expr.name) {
                "setContentView" -> {
                    val arg = evalArgs.firstOrNull()
                    if (arg is VmObject) {
                        rootViewObj = arg
                        dispatchSizeChanged(arg, canvasWidthPx, canvasHeightPx)
                    } else if (arg is String && arg.startsWith("__res_layout_")) {
                        val layoutName = arg.removePrefix("__res_layout_")
                        val xml = layouts["$layoutName.xml"] ?: layouts[layoutName]
                        if (xml != null) {
                            rootViewObj = inflateLayoutXml(xml)
                            dispatchSizeChanged(rootViewObj, canvasWidthPx, canvasHeightPx)
                        }
                    }
                    return null
                }
                "findViewById" -> {
                    val idKey = (evalArgs.firstOrNull() as? Number)?.toInt() ?: 0
                    return viewsById[idKey]
                }
                "getString" -> {
                    val key = evalArgs.firstOrNull()?.toString().orEmpty()
                    if (key.startsWith("__res_string_")) {
                        val resName = key.removePrefix("__res_string_")
                        return strings[resName] ?: appName
                    }
                    return strings[key] ?: appName
                }
                "runOnUiThread" -> {
                    evalArgs.firstOrNull()?.let { invokeCallback(it) }
                    return null
                }
                "invalidate", "postInvalidate" -> return null
                "getWidth" -> return canvasWidthPx
                "getHeight" -> return canvasHeightPx
                "getResources" -> return VmNamespace("Resources")
                "getPackageName" -> return packageName
            }
            if (thisObj != null && cls != null && findMethod(cls, expr.name, evalArgs.size) != null) {
                return callMethod(thisObj, expr.name, evalArgs)
            }
            var outer = thisObj?.outerThis
            var outerGuard = 0
            while (outer != null && outerGuard++ < 8) {
                val outerCls = outer.classDecl
                if (outerCls != null && findMethod(outerCls, expr.name, evalArgs.size) != null) {
                    return callMethod(outer, expr.name, evalArgs)
                }
                outer = outer.outerThis
            }
            if (cls != null && findMethod(cls, expr.name, evalArgs.size) != null) {
                return callStaticMethod(cls, expr.name, evalArgs)
            }
            return null
        }

        // Static / Namespace receiver check
        if (expr.target is VmExpr.Ident) {
            val tName = expr.target.name
            if (!env.contains(tName) && (thisObj == null || !thisObj.fields.containsKey(tName))) {
                classes[tName]?.let { targetCls ->
                    if (findMethod(targetCls, expr.name, evalArgs.size) != null) {
                        return callStaticMethod(targetCls, expr.name, evalArgs)
                    }
                }
                val nsResult = evalNamespaceCall(tName, expr.name, evalArgs)
                if (nsResult !== UNRESOLVED) return nsResult
            }
        }

        val recv = evalExpr(expr.target, env, thisObj, cls)
        if (recv is VmNamespace) {
            val nsResult = evalNamespaceCall(recv.name, expr.name, evalArgs)
            if (nsResult !== UNRESOLVED) return nsResult
        }
        return invokeMemberMethod(recv, expr.name, evalArgs)
    }

    private fun evalNamespaceCall(ns: String, method: String, args: List<Any?>): Any? {
        return when (ns) {
            "Math" -> when (method) {
                "abs" -> {
                    val a = args.firstOrNull()
                    when (a) {
                        is Float -> abs(a)
                        is Double -> abs(a)
                        is Long -> abs(a)
                        else -> abs(toInt(a))
                    }
                }
                "min" -> {
                    val a = args.getOrNull(0); val b = args.getOrNull(1)
                    if (a is Float || b is Float) min(toFloat(a), toFloat(b))
                    else if (a is Double || b is Double) min(toDouble(a), toDouble(b))
                    else min(toInt(a), toInt(b))
                }
                "max" -> {
                    val a = args.getOrNull(0); val b = args.getOrNull(1)
                    if (a is Float || b is Float) max(toFloat(a), toFloat(b))
                    else if (a is Double || b is Double) max(toDouble(a), toDouble(b))
                    else max(toInt(a), toInt(b))
                }
                "sqrt" -> sqrt(toDouble(args.getOrNull(0)))
                "pow" -> toDouble(args.getOrNull(0)).pow(toDouble(args.getOrNull(1)))
                "sin" -> sin(toDouble(args.getOrNull(0)))
                "cos" -> cos(toDouble(args.getOrNull(0)))
                "tan" -> tan(toDouble(args.getOrNull(0)))
                "atan2" -> atan2(toDouble(args.getOrNull(0)), toDouble(args.getOrNull(1)))
                "hypot" -> hypot(toDouble(args.getOrNull(0)), toDouble(args.getOrNull(1)))
                "floor" -> floor(toDouble(args.getOrNull(0)))
                "ceil" -> ceil(toDouble(args.getOrNull(0)))
                "round" -> round(toDouble(args.getOrNull(0))).toLong()
                "random" -> Random.nextDouble()
                else -> 0.0
            }
            "Color" -> when (method) {
                "parseColor" -> parseColorInt(args.firstOrNull()?.toString().orEmpty()) ?: 0xFF000000.toInt()
                "rgb" -> {
                    val r = toInt(args.getOrNull(0)).coerceIn(0, 255)
                    val g = toInt(args.getOrNull(1)).coerceIn(0, 255)
                    val b = toInt(args.getOrNull(2)).coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                "argb" -> {
                    val a = toInt(args.getOrNull(0)).coerceIn(0, 255)
                    val r = toInt(args.getOrNull(1)).coerceIn(0, 255)
                    val g = toInt(args.getOrNull(2)).coerceIn(0, 255)
                    val b = toInt(args.getOrNull(3)).coerceIn(0, 255)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                "HSVToColor" -> {
                    val arr = args.lastOrNull() as? FloatArray
                    val alpha = if (args.size >= 2) toInt(args.getOrNull(0)).coerceIn(0, 255) else 255
                    val hsv = FloatArray(3) { idx -> arr?.getOrNull(idx) ?: 0f }
                    val base = android.graphics.Color.HSVToColor(hsv)
                    (alpha shl 24) or (base and 0x00FFFFFF)
                }
                else -> 0xFFFFFFFF.toInt()
            }
            "Toast" -> {
                if (method == "makeText") {
                    val text = args.getOrNull(1)?.toString().orEmpty()
                    VmObject("Toast", null).apply { fields["__text"] = text }
                } else null
            }
            "Log" -> {
                val tag = args.getOrNull(0)?.toString() ?: "App"
                val msg = args.getOrNull(1)?.toString() ?: ""
                appendLog("${method.uppercase().take(1)}/$tag: $msg")
                0
            }
            "System" -> when (method) {
                "currentTimeMillis" -> System.currentTimeMillis()
                "nanoTime" -> System.nanoTime()
                else -> null
            }
            "System.out" -> {
                val msg = args.firstOrNull()?.toString().orEmpty()
                appendLog("I/System.out: $msg")
                null
            }
            "String" -> when (method) {
                "valueOf" -> args.firstOrNull()?.toString() ?: "null"
                "format" -> {
                    val fmt = args.firstOrNull()?.toString().orEmpty()
                    runCatching { String.format(fmt, *args.drop(1).toTypedArray()) }.getOrDefault(fmt)
                }
                else -> ""
            }
            "Integer" -> when (method) {
                "parseInt", "valueOf" -> args.firstOrNull()?.toString()?.trim()?.toIntOrNull() ?: 0
                "toString" -> toInt(args.firstOrNull()).toString()
                else -> 0
            }
            "Float" -> when (method) {
                "parseFloat", "valueOf" -> args.firstOrNull()?.toString()?.trim()?.toFloatOrNull() ?: 0f
                else -> 0f
            }
            "Double" -> when (method) {
                "parseDouble", "valueOf" -> args.firstOrNull()?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            "Arrays" -> when (method) {
                "asList" -> {
                    if (args.size == 1 && args[0] is Array<*>) {
                        (args[0] as Array<*>).toMutableList()
                    } else {
                        args.toMutableList()
                    }
                }
                "fill" -> {
                    val arr = args.getOrNull(0)
                    val v = args.getOrNull(1)
                    when (arr) {
                        is IntArray -> arr.fill(toInt(v))
                        is FloatArray -> arr.fill(toFloat(v))
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            (arr as Array<Any?>).fill(v)
                        }
                    }
                    null
                }
                else -> null
            }
            "Collections" -> when (method) {
                "emptyList" -> mutableListOf<Any?>()
                "emptySet" -> linkedSetOf<Any?>()
                "emptyMap" -> linkedMapOf<Any?, Any?>()
                "reverse" -> { (args.firstOrNull() as? MutableList<*>)?.reverse(); null }
                else -> null
            }
            else -> UNRESOLVED
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeMemberMethod(recv: Any?, name: String, args: List<Any?>): Any? {
        if (recv == null) return null
        // User-defined class methods first
        if (recv is VmObject && recv.classDecl != null && findMethod(recv.classDecl, name, args.size) != null) {
            return callMethod(recv, name, args)
        }
        // Standard library / Android SDK object methods
        return when (recv) {
            is String -> when (name) {
                "length" -> recv.length
                "isEmpty" -> recv.isEmpty()
                "isBlank" -> recv.isBlank()
                "trim" -> recv.trim()
                "toLowerCase", "lowercase" -> recv.lowercase()
                "toUpperCase", "uppercase" -> recv.uppercase()
                "contains" -> recv.contains(args.firstOrNull()?.toString().orEmpty())
                "startsWith" -> recv.startsWith(args.firstOrNull()?.toString().orEmpty())
                "endsWith" -> recv.endsWith(args.firstOrNull()?.toString().orEmpty())
                "equals" -> recv == args.firstOrNull()?.toString()
                "equalsIgnoreCase" -> recv.equals(args.firstOrNull()?.toString(), ignoreCase = true)
                "substring" -> {
                    val s = toInt(args.getOrNull(0)).coerceIn(0, recv.length)
                    if (args.size >= 2) recv.substring(s, toInt(args[1]).coerceIn(s, recv.length))
                    else recv.substring(s)
                }
                "charAt" -> recv.getOrNull(toInt(args.getOrNull(0))) ?: '\u0000'
                "replace" -> recv.replace(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty())
                "split" -> {
                    val sep = args.getOrNull(0)?.toString() ?: ","
                    runCatching { recv.split(Regex(sep)).toTypedArray() }
                        .getOrElse { recv.split(sep).toTypedArray() }
                }
                else -> null
            }
            is StringBuilder -> when (name) {
                "append" -> { recv.append(args.firstOrNull()?.toString() ?: "null"); recv }
                "toString" -> recv.toString()
                "length" -> recv.length
                "setLength" -> { recv.setLength(toInt(args.firstOrNull()).coerceAtLeast(0)); null }
                else -> recv
            }
            is MutableList<*> -> {
                val list = recv as MutableList<Any?>
                when (name) {
                    "add" -> {
                        if (args.size >= 2) {
                            list.add(toInt(args[0]).coerceIn(0, list.size), args[1])
                        } else {
                            list.add(args.firstOrNull())
                        }
                        true
                    }
                    "get" -> list.getOrNull(toInt(args.firstOrNull()))
                    "set" -> {
                        val i = toInt(args.getOrNull(0))
                        if (i in list.indices) list.set(i, args.getOrNull(1)) else null
                    }
                    "remove" -> {
                        val arg = args.firstOrNull()
                        if (arg is Int && arg in list.indices) list.removeAt(arg) else list.remove(arg)
                    }
                    "size" -> list.size
                    "isEmpty" -> list.isEmpty()
                    "clear" -> { list.clear(); null }
                    "contains" -> list.contains(args.firstOrNull())
                    else -> null
                }
            }
            is MutableMap<*, *> -> {
                val map = recv as MutableMap<Any?, Any?>
                when (name) {
                    "put" -> map.put(args.getOrNull(0), args.getOrNull(1))
                    "get" -> map[args.getOrNull(0)]
                    "getOrDefault" -> map[args.getOrNull(0)] ?: args.getOrNull(1)
                    "containsKey" -> map.containsKey(args.getOrNull(0))
                    "remove" -> map.remove(args.getOrNull(0))
                    "size" -> map.size
                    "clear" -> { map.clear(); null }
                    else -> null
                }
            }
            is MutableSet<*> -> {
                val set = recv as MutableSet<Any?>
                when (name) {
                    "add" -> set.add(args.firstOrNull())
                    "remove" -> set.remove(args.firstOrNull())
                    "contains" -> set.contains(args.firstOrNull())
                    "size" -> set.size
                    "clear" -> { set.clear(); null }
                    else -> null
                }
            }
            is Random -> when (name) {
                "nextInt" -> {
                    val bound = toInt(args.firstOrNull())
                    if (args.isNotEmpty() && bound > 0) recv.nextInt(bound) else recv.nextInt()
                }
                "nextFloat" -> recv.nextFloat()
                "nextDouble" -> recv.nextDouble()
                "nextBoolean" -> recv.nextBoolean()
                else -> 0
            }
            is VmRect -> when (name) {
                "set" -> {
                    recv.left = toFloat(args.getOrNull(0))
                    recv.top = toFloat(args.getOrNull(1))
                    recv.right = toFloat(args.getOrNull(2))
                    recv.bottom = toFloat(args.getOrNull(3))
                    null
                }
                "width" -> recv.right - recv.left
                "height" -> recv.bottom - recv.top
                "centerX" -> (recv.left + recv.right) * 0.5f
                "centerY" -> (recv.top + recv.bottom) * 0.5f
                "contains" -> {
                    val x = toFloat(args.getOrNull(0)); val y = toFloat(args.getOrNull(1))
                    x >= recv.left && x <= recv.right && y >= recv.top && y <= recv.bottom
                }
                "intersect" -> {
                    val o = args.firstOrNull() as? VmRect ?: return false
                    recv.left < o.right && recv.right > o.left && recv.top < o.bottom && recv.bottom > o.top
                }
                else -> null
            }
            is VmPath -> when (name) {
                "reset" -> { recv.segments.clear(); null }
                "moveTo" -> { recv.segments.add(VmPathSegment.MoveTo(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)))); null }
                "lineTo" -> { recv.segments.add(VmPathSegment.LineTo(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)))); null }
                "quadTo" -> {
                    recv.segments.add(
                        VmPathSegment.QuadTo(
                            toFloat(args.getOrNull(0)),
                            toFloat(args.getOrNull(1)),
                            toFloat(args.getOrNull(2)),
                            toFloat(args.getOrNull(3)),
                        ),
                    )
                    null
                }
                "close" -> { recv.segments.add(VmPathSegment.Close); null }
                else -> null
            }
            is VmObject -> invokeAndroidObjectMethod(recv, name, args)
            else -> null
        }
    }

    private fun invokeAndroidObjectMethod(recv: VmObject, name: String, args: List<Any?>): Any? {
        when (recv.typeName) {
            "Canvas" -> {
                @Suppress("UNCHECKED_CAST")
                val ops = recv.nativeTag as? MutableList<VmCanvasOp> ?: return null
                when (name) {
                    "drawColor" -> ops.add(VmCanvasOp.FillColor(toInt(args.firstOrNull())))
                    "drawARGB" -> {
                        val a = toInt(args.getOrNull(0)).coerceIn(0, 255)
                        val r = toInt(args.getOrNull(1)).coerceIn(0, 255)
                        val g = toInt(args.getOrNull(2)).coerceIn(0, 255)
                        val b = toInt(args.getOrNull(3)).coerceIn(0, 255)
                        ops.add(VmCanvasOp.FillColor((a shl 24) or (r shl 16) or (g shl 8) or b))
                    }
                    "drawRGB" -> {
                        val r = toInt(args.getOrNull(0)).coerceIn(0, 255)
                        val g = toInt(args.getOrNull(1)).coerceIn(0, 255)
                        val b = toInt(args.getOrNull(2)).coerceIn(0, 255)
                        ops.add(VmCanvasOp.FillColor((0xFF shl 24) or (r shl 16) or (g shl 8) or b))
                    }
                    "drawRect" -> {
                        val paint = args.lastOrNull() as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        if (args.size == 2 && args[0] is VmRect) {
                            val r = args[0] as VmRect
                            ops.add(VmCanvasOp.DrawRect(r.left, r.top, r.right, r.bottom, 0f, argb, stroke, sw))
                        } else if (args.size >= 5) {
                            ops.add(
                                VmCanvasOp.DrawRect(
                                    toFloat(args[0]), toFloat(args[1]), toFloat(args[2]), toFloat(args[3]),
                                    0f, argb, stroke, sw,
                                ),
                            )
                        }
                    }
                    "drawRoundRect" -> {
                        val paint = args.lastOrNull() as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        if (args.size == 4 && args[0] is VmRect) {
                            val r = args[0] as VmRect
                            ops.add(VmCanvasOp.DrawRect(r.left, r.top, r.right, r.bottom, toFloat(args[1]), argb, stroke, sw))
                        } else if (args.size >= 7) {
                            ops.add(
                                VmCanvasOp.DrawRect(
                                    toFloat(args[0]), toFloat(args[1]), toFloat(args[2]), toFloat(args[3]),
                                    toFloat(args[4]), argb, stroke, sw,
                                ),
                            )
                        }
                    }
                    "drawCircle" -> {
                        val paint = args.getOrNull(3) as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        ops.add(VmCanvasOp.DrawCircle(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)), toFloat(args.getOrNull(2)), argb, stroke, sw))
                    }
                    "drawOval" -> {
                        val paint = args.lastOrNull() as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        if (args.size == 2 && args[0] is VmRect) {
                            val r = args[0] as VmRect
                            ops.add(VmCanvasOp.DrawOval(r.left, r.top, r.right, r.bottom, argb, stroke, sw))
                        } else if (args.size >= 5) {
                            ops.add(VmCanvasOp.DrawOval(toFloat(args[0]), toFloat(args[1]), toFloat(args[2]), toFloat(args[3]), argb, stroke, sw))
                        }
                    }
                    "drawLine" -> {
                        val paint = args.getOrNull(4) as? VmObject
                        val (argb, _, sw) = extractPaintProps(paint)
                        ops.add(VmCanvasOp.DrawLine(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)), toFloat(args.getOrNull(2)), toFloat(args.getOrNull(3)), argb, sw))
                    }
                    "drawArc" -> {
                        val paint = args.lastOrNull() as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        if (args.size >= 5 && args[0] is VmRect) {
                            val r = args[0] as VmRect
                            ops.add(VmCanvasOp.DrawArc(r.left, r.top, r.right, r.bottom, toFloat(args[1]), toFloat(args[2]), toBoolean(args[3]), argb, stroke, sw))
                        } else if (args.size >= 8) {
                            ops.add(VmCanvasOp.DrawArc(toFloat(args[0]), toFloat(args[1]), toFloat(args[2]), toFloat(args[3]), toFloat(args[4]), toFloat(args[5]), toBoolean(args[6]), argb, stroke, sw))
                        }
                    }
                    "drawPath" -> {
                        val p = args.getOrNull(0) as? VmPath ?: return null
                        val paint = args.getOrNull(1) as? VmObject
                        val (argb, stroke, sw) = extractPaintProps(paint)
                        ops.add(VmCanvasOp.DrawPath(p.segments.toList(), argb, stroke, sw))
                    }
                    "drawText" -> {
                        val text = args.getOrNull(0)?.toString().orEmpty()
                        val x = toFloat(args.getOrNull(1))
                        val y = toFloat(args.getOrNull(2))
                        val paint = args.getOrNull(3) as? VmObject
                        val (argb, _, _) = extractPaintProps(paint)
                        val textSize = (paint?.fields?.get("__textSize") as? Number)?.toFloat() ?: 36f
                        val align = paint?.fields?.get("__textAlign")?.toString() ?: "LEFT"
                        val bold = paint?.fields?.get("__isBold") == true
                        ops.add(VmCanvasOp.DrawText(text, x, y, textSize, argb, align, bold))
                    }
                    "save" -> { ops.add(VmCanvasOp.Save); return 1 }
                    "restore" -> ops.add(VmCanvasOp.Restore)
                    "translate" -> ops.add(VmCanvasOp.Translate(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1))))
                    "rotate" -> ops.add(VmCanvasOp.Rotate(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)), toFloat(args.getOrNull(2))))
                    "scale" -> ops.add(VmCanvasOp.Scale(toFloat(args.getOrNull(0)), toFloat(args.getOrNull(1)), toFloat(args.getOrNull(2)), toFloat(args.getOrNull(3))))
                    "getWidth" -> return canvasWidthPx
                    "getHeight" -> return canvasHeightPx
                }
                return null
            }
            "Paint" -> {
                when (name) {
                    "setColor" -> recv.fields["__color"] = toInt(args.firstOrNull())
                    "getColor" -> return (recv.fields["__color"] as? Number)?.toInt() ?: 0xFFFFFFFF.toInt()
                    "setAlpha" -> recv.fields["__alpha"] = toInt(args.firstOrNull()).coerceIn(0, 255)
                    "setStrokeWidth" -> recv.fields["__strokeWidth"] = toFloat(args.firstOrNull())
                    "setStyle" -> recv.fields["__style"] = args.firstOrNull()?.toString() ?: "FILL"
                    "setTextSize" -> recv.fields["__textSize"] = toFloat(args.firstOrNull())
                    "setTextAlign" -> recv.fields["__textAlign"] = args.firstOrNull()?.toString() ?: "LEFT"
                    "setFakeBoldText" -> recv.fields["__isBold"] = toBoolean(args.firstOrNull())
                    "setTypeface" -> recv.fields["__isBold"] = args.firstOrNull()?.toString()?.contains("BOLD") == true
                    "setAntiAlias", "setDither" -> Unit
                }
                return null
            }
            "MotionEvent" -> return when (name) {
                "getAction", "getActionMasked" -> (recv.fields["__action"] as? Number)?.toInt() ?: 0
                "getX" -> (recv.fields["__x"] as? Number)?.toFloat() ?: 0f
                "getY" -> (recv.fields["__y"] as? Number)?.toFloat() ?: 0f
                "getPointerCount" -> 1
                "getPointerId" -> 0
                else -> 0
            }
            "SurfaceHolder" -> {
                val ownerView = recv.fields["__ownerView"] as? VmObject
                when (name) {
                    "addCallback" -> {
                        val cb = args.firstOrNull() as? VmObject
                        if (cb != null && cb.classDecl != null) {
                            runSafely {
                                callMethod(cb, "surfaceCreated", listOf(recv))
                                callMethod(cb, "surfaceChanged", listOf(recv, 0, canvasWidthPx, canvasHeightPx))
                            }
                        }
                        return null
                    }
                    "lockCanvas" -> {
                        val c = VmObject("Canvas", null).apply {
                            nativeTag = mutableListOf<VmCanvasOp>()
                        }
                        recv.fields["__pendingCanvas"] = c
                        return c
                    }
                    "unlockCanvasAndPost" -> {
                        val c = (args.firstOrNull() as? VmObject) ?: (recv.fields["__pendingCanvas"] as? VmObject)
                        val ops = (c?.nativeTag as? List<*>)?.filterIsInstance<VmCanvasOp>().orEmpty()
                        if (ownerView != null) {
                            ownerView.fields["__surfaceOps"] = ops
                        }
                        return null
                    }
                }
                return null
            }
            "Handler" -> {
                when (name) {
                    "post" -> {
                        val r = args.firstOrNull()
                        synchronized(stateLock) {
                            pendingTimers.add(ScheduledTask(nextTaskId++, r, System.currentTimeMillis() + 16L))
                        }
                        return true
                    }
                    "postDelayed" -> {
                        val r = args.getOrNull(0)
                        val delayMs = toLong(args.getOrNull(1)).coerceAtLeast(16L)
                        synchronized(stateLock) {
                            pendingTimers.add(ScheduledTask(nextTaskId++, r, System.currentTimeMillis() + delayMs))
                        }
                        return true
                    }
                    "removeCallbacks" -> {
                        val r = args.firstOrNull()
                        if (r != null) {
                            synchronized(stateLock) {
                                pendingTimers.removeAll { it.runnable === r }
                            }
                        }
                    }
                    "removeCallbacksAndMessages" -> synchronized(stateLock) { pendingTimers.clear() }
                }
                return null
            }
            "Toast" -> {
                if (name == "show") {
                    val txt = recv.fields["__text"]?.toString().orEmpty()
                    if (txt.isNotBlank()) {
                        synchronized(stateLock) {
                            toastQueue.add(txt)
                        }
                    }
                }
                return null
            }
            "AlertDialog.Builder", "Builder" -> {
                when (name) {
                    "setTitle" -> recv.fields["__title"] = resolveMaybeString(args.firstOrNull())
                    "setMessage" -> recv.fields["__message"] = resolveMaybeString(args.firstOrNull())
                    "setPositiveButton" -> {
                        recv.fields["__posListener"] = args.getOrNull(1)
                    }
                    "setNegativeButton" -> {
                        recv.fields["__negListener"] = args.getOrNull(1)
                    }
                    "show", "create" -> {
                        activeDialog = (recv.fields["__title"]?.toString() ?: appName) to recv.fields["__message"]?.toString().orEmpty()
                        val pos = recv.fields["__posListener"]
                        val neg = recv.fields["__negListener"]
                        activeDialogPositive = if (pos != null) ({ invokeCallback(pos) }) else null
                        activeDialogNegative = if (neg != null) ({ invokeCallback(neg) }) else null
                    }
                }
                return recv
            }
            "GradientDrawable" -> {
                when (name) {
                    "setColor" -> recv.fields["__color"] = toInt(args.firstOrNull())
                    "setCornerRadius" -> recv.fields["__cornerRadius"] = toFloat(args.firstOrNull())
                    "setStroke" -> recv.fields["__strokeColor"] = toInt(args.getOrNull(1))
                }
                return null
            }
            "LinearLayout.LayoutParams", "LayoutParams" -> {
                if (name == "setMargins") {
                    recv.fields["__margin"] = toInt(args.getOrNull(0))
                }
                return null
            }
        }

        // General View / ViewGroup / TextView / EditText methods
        when (name) {
            "addView" -> {
                val child = args.firstOrNull() as? VmObject ?: return null
                if (child === recv) return null
                val second = args.getOrNull(1)
                val lp = second as? VmObject
                if (lp != null) {
                    (lp.fields["__weight"] as? Number)?.let { child.fields["__layoutWeight"] = it.toFloat() }
                }
                @Suppress("UNCHECKED_CAST")
                val list = recv.nativeTag as? MutableList<VmObject> ?: mutableListOf<VmObject>().also { recv.nativeTag = it }
                if (second is Number && lp == null) {
                    val idx = second.toInt().coerceIn(0, list.size)
                    list.add(idx, child)
                } else {
                    list.add(child)
                }
            }
            "removeAllViews" -> (recv.nativeTag as? MutableList<*>)?.clear()
            "setLayoutParams" -> {
                val lp = args.firstOrNull() as? VmObject
                if (lp != null) {
                    (lp.fields["__weight"] as? Number)?.let { recv.fields["__layoutWeight"] = it.toFloat() }
                }
            }
            "setOrientation" -> recv.fields["__orientation"] = toInt(args.firstOrNull())
            "setGravity" -> recv.fields["__gravity"] = toInt(args.firstOrNull())
            "setColumnCount" -> recv.fields["__columnCount"] = toInt(args.firstOrNull())
            "setRowCount" -> recv.fields["__rowCount"] = toInt(args.firstOrNull())
            "setBackgroundColor" -> recv.fields["__backgroundColor"] = toInt(args.firstOrNull())
            "setBackground", "setBackgroundDrawable" -> recv.fields["__backgroundDrawable"] = args.firstOrNull()
            "setPadding" -> {
                recv.fields["__padL"] = toInt(args.getOrNull(0))
                recv.fields["__padT"] = toInt(args.getOrNull(1))
                recv.fields["__padR"] = toInt(args.getOrNull(2))
                recv.fields["__padB"] = toInt(args.getOrNull(3))
            }
            "setText" -> recv.fields["__text"] = resolveMaybeString(args.firstOrNull())
            "getText" -> return recv.fields["__text"]?.toString().orEmpty()
            "setHint" -> recv.fields["__hint"] = resolveMaybeString(args.firstOrNull())
            "setTextSize" -> recv.fields["__textSize"] = toFloat(args.lastOrNull())
            "setTextColor" -> recv.fields["__textColor"] = toInt(args.firstOrNull())
            "setTypeface" -> {
                val s = args.joinToString(" ")
                recv.fields["__isBold"] = s.contains("BOLD") || s.contains("1")
                recv.fields["__isMono"] = s.contains("MONOSPACE")
            }
            "setVisibility" -> recv.fields["__visibility"] = toInt(args.firstOrNull())
            "setOnClickListener" -> recv.fields["__onClickListener"] = args.firstOrNull()
            "setId" -> {
                val id = toInt(args.firstOrNull())
                viewsById[id] = recv
            }
            "findViewById" -> return viewsById[toInt(args.firstOrNull())]
            "getWidth", "getMeasuredWidth" -> return canvasWidthPx
            "getHeight", "getMeasuredHeight" -> return canvasHeightPx
            "getHolder" -> {
                val existing = recv.fields["__holder"] as? VmObject
                if (existing != null) return existing
                val holder = VmObject("SurfaceHolder", null).apply {
                    fields["__ownerView"] = recv
                }
                recv.fields["__holder"] = holder
                return holder
            }
            "invalidate", "postInvalidate", "requestLayout" -> Unit
        }
        return null
    }

    private fun extractPaintProps(paint: VmObject?): Triple<Int, Boolean, Float> {
        if (paint == null) return Triple(0xFFFFFFFF.toInt(), false, 2f)
        val rawColor = (paint.fields["__color"] as? Number)?.toInt() ?: 0xFFFFFFFF.toInt()
        val alphaOverride = (paint.fields["__alpha"] as? Number)?.toInt()
        val argb = if (alphaOverride != null) {
            (alphaOverride.coerceIn(0, 255) shl 24) or (rawColor and 0x00FFFFFF)
        } else rawColor
        val stroke = paint.fields["__style"]?.toString() == "STROKE"
        val sw = (paint.fields["__strokeWidth"] as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 2f
        return Triple(argb, stroke, sw)
    }

    private fun resolveMaybeString(v: Any?): String {
        val s = v?.toString().orEmpty()
        return if (s.startsWith("__res_string_")) {
            val k = s.removePrefix("__res_string_")
            strings[k] ?: appName
        } else {
            s
        }
    }

    private fun evalNewObject(expr: VmExpr.NewObject, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any {
        val evalArgs = expr.args.map { evalExpr(it, env, thisObj, cls) }
        val simpleName = expr.typeName.substringAfterLast('.')
        // Check anonymous class body first (e.g. new View.OnClickListener() { ... } or new Runnable() { ... })
        if (expr.anonymousBody != null) {
            val anonName = "Anon_${simpleName}_${System.nanoTime()}"
            val anonCls = VmClass(
                name = anonName,
                superName = simpleName,
                fields = mutableListOf(),
                constructors = mutableListOf(),
                methods = expr.anonymousBody.toMutableList(),
            )
            return createInstance(anonCls, evalArgs, outerThis = thisObj, outerScope = env)
        }
        // User-defined class?
        classes[expr.typeName]?.let { return createInstance(it, evalArgs) }
        classes[simpleName]?.let { return createInstance(it, evalArgs) }

        return when (simpleName) {
            "ArrayList", "LinkedList" -> mutableListOf<Any?>()
            "HashMap", "LinkedHashMap" -> mutableMapOf<Any?, Any?>()
            "HashSet", "LinkedHashSet" -> mutableSetOf<Any?>()
            "StringBuilder" -> StringBuilder(evalArgs.firstOrNull()?.toString().orEmpty())
            "Random" -> Random.Default
            "Rect", "RectF" -> VmRect(
                left = toFloat(evalArgs.getOrNull(0)),
                top = toFloat(evalArgs.getOrNull(1)),
                right = toFloat(evalArgs.getOrNull(2)),
                bottom = toFloat(evalArgs.getOrNull(3)),
            )
            "Path" -> VmPath()
            "Paint" -> VmObject("Paint", null).apply {
                fields["__color"] = 0xFFFFFFFF.toInt()
                fields["__strokeWidth"] = 2f
                fields["__style"] = "FILL"
                fields["__textSize"] = 36f
            }
            "Handler" -> VmObject("Handler", null)
            "Builder" -> VmObject("AlertDialog.Builder", null)
            "GradientDrawable" -> VmObject("GradientDrawable", null)
            "LayoutParams" -> VmObject("LinearLayout.LayoutParams", null).apply {
                if (evalArgs.size >= 3) fields["__weight"] = toFloat(evalArgs[2])
            }
            "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout" -> VmObject(simpleName, null).apply {
                nativeTag = mutableListOf<VmObject>()
                fields["__orientation"] = 1
            }
            else -> VmObject(simpleName, null)
        }
    }

    private fun evalNewArray(expr: VmExpr.NewArray, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any {
        if (expr.initializer != null) {
            return expr.initializer.elements.map { evalExpr(it, env, thisObj, cls) }.toTypedArray()
        }
        val dims = expr.dimExprs.map { toInt(evalExpr(it, env, thisObj, cls)).coerceIn(0, 2048) }
        val firstDim = dims.firstOrNull() ?: 0
        if (dims.size >= 2) {
            val secondDim = dims[1]
            return Array<Any?>(firstDim) {
                when (expr.elemType) {
                    "int" -> IntArray(secondDim)
                    "float" -> FloatArray(secondDim)
                    "double" -> DoubleArray(secondDim)
                    "boolean" -> BooleanArray(secondDim)
                    else -> arrayOfNulls<Any?>(secondDim)
                }
            }
        }
        return when (expr.elemType) {
            "int" -> IntArray(firstDim)
            "float" -> FloatArray(firstDim)
            "double" -> DoubleArray(firstDim)
            "boolean" -> BooleanArray(firstDim)
            else -> arrayOfNulls<Any?>(firstDim)
        }
    }

    private fun evalBinary(expr: VmExpr.Binary, env: VmScope, thisObj: VmObject?, cls: VmClass?): Any? {
        if (expr.op == "&&") {
            return toBoolean(evalExpr(expr.left, env, thisObj, cls)) &&
                toBoolean(evalExpr(expr.right, env, thisObj, cls))
        }
        if (expr.op == "||") {
            return toBoolean(evalExpr(expr.left, env, thisObj, cls)) ||
                toBoolean(evalExpr(expr.right, env, thisObj, cls))
        }
        val l = evalExpr(expr.left, env, thisObj, cls)
        val r = evalExpr(expr.right, env, thisObj, cls)
        return when (expr.op) {
            "+" -> evalPlus(l, r)
            "-", "*", "/", "%" -> evalNumOp(l, r, expr.op)
            "&" -> toInt(l) and toInt(r)
            "|" -> toInt(l) or toInt(r)
            "^" -> toInt(l) xor toInt(r)
            "<<" -> toInt(l) shl toInt(r)
            ">>" -> toInt(l) shr toInt(r)
            ">>>" -> toInt(l) ushr toInt(r)
            "==" -> valuesEqual(l, r)
            "!=" -> !valuesEqual(l, r)
            "<" -> toDouble(l) < toDouble(r)
            "<=" -> toDouble(l) <= toDouble(r)
            ">" -> toDouble(l) > toDouble(r)
            ">=" -> toDouble(l) >= toDouble(r)
            else -> null
        }
    }

    private fun evalPlus(l: Any?, r: Any?): Any {
        if (l is String || r is String) return (l?.toString() ?: "null") + (r?.toString() ?: "null")
        return evalNumOp(l, r, "+")
    }

    private fun evalNumOp(l: Any?, r: Any?, op: String): Any {
        if (l is Double || r is Double) {
            val a = toDouble(l); val b = toDouble(r)
            return when (op) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b
                "/" -> if (b == 0.0) 0.0 else a / b
                "%" -> if (b == 0.0) 0.0 else a % b
                else -> 0.0
            }
        }
        if (l is Float || r is Float) {
            val a = toFloat(l); val b = toFloat(r)
            return when (op) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b
                "/" -> if (b == 0f) 0f else a / b
                "%" -> if (b == 0f) 0f else a % b
                else -> 0f
            }
        }
        if (l is Long || r is Long) {
            val a = toLong(l); val b = toLong(r)
            return when (op) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b
                "/" -> if (b == 0L) 0L else a / b
                "%" -> if (b == 0L) 0L else a % b
                else -> 0L
            }
        }
        val a = toInt(l); val b = toInt(r)
        return when (op) {
            "+" -> a + b; "-" -> a - b; "*" -> a * b
            "/" -> if (b == 0) 0 else a / b
            "%" -> if (b == 0) 0 else a % b
            else -> 0
        }
    }

    private fun inflateLayoutXml(xml: String): VmObject {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml.trim())))
        }.getOrElse { err ->
            appendLog("W/DroidForgeVM: Layout XML parse fallback: ${err.message ?: err.javaClass.simpleName}")
            return VmObject("LinearLayout", null).apply {
                nativeTag = mutableListOf<VmObject>()
                fields["__orientation"] = 1
            }
        }
        val androidNs = "http://schemas.android.com/apk/res/android"

        fun buildEl(el: Element): VmObject {
            val fullTag = el.tagName
            val tag = (el.localName ?: fullTag).substringAfterLast('.')
            val customCls = classes[fullTag] ?: classes[tag]
            val obj = if (customCls != null) {
                createInstance(customCls, emptyList())
            } else {
                VmObject(tag, null)
            }
            if (resolveNativeWidgetType(tag, customCls) in setOf("LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout")) {
                if (obj.nativeTag == null) obj.nativeTag = mutableListOf<VmObject>()
                if (!obj.fields.containsKey("__orientation")) obj.fields["__orientation"] = 1
            }
            val rawId = el.getAttributeNS(androidNs, "id").ifBlank { el.getAttribute("android:id") }
            if (rawId.isNotBlank()) {
                val idName = rawId.substringAfterLast('/')
                obj.fields["__idName"] = idName
                viewsById[idName.hashCode()] = obj
            }
            val rawText = el.getAttributeNS(androidNs, "text").ifBlank { el.getAttribute("android:text") }
            if (rawText.isNotBlank()) {
                obj.fields["__text"] = if (rawText.startsWith("@string/")) {
                    strings[rawText.removePrefix("@string/")] ?: rawText
                } else rawText
            }
            val rawHint = el.getAttributeNS(androidNs, "hint").ifBlank { el.getAttribute("android:hint") }
            if (rawHint.isNotBlank()) obj.fields["__hint"] = rawHint

            val rawBg = el.getAttributeNS(androidNs, "background").ifBlank { el.getAttribute("android:background") }
            if (rawBg.isNotBlank()) parseColorInt(rawBg)?.let { obj.fields["__backgroundColor"] = it }

            val rawTxtColor = el.getAttributeNS(androidNs, "textColor").ifBlank { el.getAttribute("android:textColor") }
            if (rawTxtColor.isNotBlank()) parseColorInt(rawTxtColor)?.let { obj.fields["__textColor"] = it }

            val rawSize = el.getAttributeNS(androidNs, "textSize").ifBlank { el.getAttribute("android:textSize") }
            rawSize.replace("sp", "").replace("dp", "").trim().toFloatOrNull()?.let {
                obj.fields["__textSize"] = it
            }

            val rawOri = el.getAttributeNS(androidNs, "orientation").ifBlank { el.getAttribute("android:orientation") }
            if (rawOri == "horizontal") obj.fields["__orientation"] = 0

            val rawCols = el.getAttributeNS(androidNs, "columnCount").ifBlank { el.getAttribute("android:columnCount") }
            rawCols.toIntOrNull()?.let { obj.fields["__columnCount"] = it }

            val rawWeight = el.getAttributeNS(androidNs, "layout_weight").ifBlank { el.getAttribute("android:layout_weight") }
            rawWeight.toFloatOrNull()?.let { obj.fields["__layoutWeight"] = it }

            val rawPad = el.getAttributeNS(androidNs, "padding").ifBlank { el.getAttribute("android:padding") }
            rawPad.replace("dp", "").replace("px", "").trim().toIntOrNull()?.let { p ->
                val px = p * 3
                obj.fields["__padL"] = px
                obj.fields["__padT"] = px
                obj.fields["__padR"] = px
                obj.fields["__padB"] = px
            }

            val rawVis = el.getAttributeNS(androidNs, "visibility").ifBlank { el.getAttribute("android:visibility") }
            when (rawVis) {
                "gone" -> obj.fields["__visibility"] = 8
                "invisible" -> obj.fields["__visibility"] = 4
                "visible" -> obj.fields["__visibility"] = 0
            }

            @Suppress("UNCHECKED_CAST")
            val childrenList = obj.nativeTag as? MutableList<VmObject>
            if (childrenList != null) {
                val nodes = el.childNodes
                for (i in 0 until nodes.length) {
                    val child = nodes.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        childrenList.add(buildEl(child as Element))
                    }
                }
            }
            return obj
        }

        return buildEl(doc.documentElement)
    }

    private fun parseColorInt(raw: String): Int? {
        val clean = raw.trim()
        if (clean.startsWith("@color/")) return colors[clean.removePrefix("@color/")]
        if (!clean.startsWith("#")) return null
        val hex = clean.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        }.getOrNull()
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) {
            return if (a is Double || b is Double || a is Float || b is Float) {
                a.toDouble() == b.toDouble()
            } else {
                a.toLong() == b.toLong()
            }
        }
        return a == b
    }

    private fun negateNumber(v: Any?): Any = when (v) {
        is Float -> -v
        is Double -> -v
        is Long -> -v
        else -> -toInt(v)
    }

    private fun castValue(v: Any?, targetType: String): Any? = when (targetType) {
        "int" -> toInt(v)
        "float" -> toFloat(v)
        "double" -> toDouble(v)
        "long" -> toLong(v)
        "boolean" -> toBoolean(v)
        "String" -> v?.toString()
        else -> v
    }

    private fun toInt(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is Char -> v.code
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun toLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun toFloat(v: Any?): Float = when (v) {
        is Number -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        is String -> v.toFloatOrNull() ?: 0f
        else -> 0f
    }

    private fun toDouble(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun toBoolean(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v != null
    }

    companion object {
        private val UNRESOLVED = Any()
    }
}

// --- AST & Tokenizer / Recursive-Descent Parser ---

internal class VmScope(val parent: VmScope?) {
    private val vars = linkedMapOf<String, Any?>()

    operator fun contains(name: String): Boolean = vars.containsKey(name) || (parent?.contains(name) == true)
    fun get(name: String): Any? = if (vars.containsKey(name)) vars[name] else parent?.get(name)
    fun define(name: String, value: Any?) {
        vars[name] = value
    }
    fun set(name: String, value: Any?) {
        if (vars.containsKey(name)) vars[name] = value
        else if (parent != null && parent.contains(name)) parent.set(name, value)
        else vars[name] = value
    }
}

internal class VmObject(
    val typeName: String,
    val classDecl: VmClass?,
) {
    val fields = linkedMapOf<String, Any?>()
    var nativeTag: Any? = null
    var outerThis: VmObject? = null
    var outerScope: VmScope? = null
}

private class VmLambda(
    val params: List<String>,
    val body: VmStmt,
    val closure: VmScope,
    val thisObj: VmObject?,
    val ownerClass: VmClass?,
)

private data class VmNamespace(val name: String)
private data class VmRect(var left: Float, var top: Float, var right: Float, var bottom: Float)
private class VmPath { val segments = mutableListOf<VmPathSegment>() }

internal data class VmField(val type: String, val name: String, val isStatic: Boolean, val initExpr: VmExpr?)
internal data class VmMethod(
    val name: String,
    val returnType: String,
    val params: List<Pair<String, String>>,
    val body: List<VmStmt>,
    val isStatic: Boolean,
)
internal data class VmClass(
    val name: String,
    val superName: String?,
    val fields: MutableList<VmField>,
    val constructors: MutableList<VmMethod>,
    val methods: MutableList<VmMethod>,
    val staticFields: MutableMap<String, Any?> = linkedMapOf(),
)

internal sealed interface VmStmt {
    data class Block(val stmts: List<VmStmt>) : VmStmt
    data class ExprStmt(val expr: VmExpr) : VmStmt
    data class VarDecl(val type: String, val decls: List<Pair<String, VmExpr?>>) : VmStmt
    data class Return(val expr: VmExpr?) : VmStmt
    data object Break : VmStmt
    data object Continue : VmStmt
    data class If(val cond: VmExpr, val thenBranch: VmStmt, val elseBranch: VmStmt?) : VmStmt
    data class While(val cond: VmExpr, val body: VmStmt) : VmStmt
    data class DoWhile(val body: VmStmt, val cond: VmExpr) : VmStmt
    data class For(val init: VmStmt?, val cond: VmExpr?, val update: List<VmExpr>, val body: VmStmt) : VmStmt
    data class ForEach(val varName: String, val iterable: VmExpr, val body: VmStmt) : VmStmt
    data class Switch(val expr: VmExpr, val cases: List<SwitchArm>) : VmStmt
    data class SwitchArm(val caseExprs: List<VmExpr>, val stmts: List<VmStmt>)
    data class TryCatch(val tryBlock: VmStmt, val catchVar: String?, val catchBlock: VmStmt?) : VmStmt
    data object Empty : VmStmt
}

internal sealed interface VmExpr {
    data class Literal(val value: Any?) : VmExpr
    data object This : VmExpr
    data class Ident(val name: String) : VmExpr
    data class Unary(val op: String, val expr: VmExpr) : VmExpr
    data class Postfix(val expr: VmExpr, val op: String) : VmExpr
    data class Cast(val targetType: String, val expr: VmExpr) : VmExpr
    data class InstanceOf(val expr: VmExpr, val typeName: String) : VmExpr
    data class Ternary(val cond: VmExpr, val thenExpr: VmExpr, val elseExpr: VmExpr) : VmExpr
    data class Binary(val op: String, val left: VmExpr, val right: VmExpr) : VmExpr
    data class Assign(val lhs: VmExpr, val op: String, val rhs: VmExpr) : VmExpr
    data class FieldAccess(val target: VmExpr, val field: String) : VmExpr
    data class IndexAccess(val target: VmExpr, val index: VmExpr) : VmExpr
    data class Call(val target: VmExpr?, val name: String, val args: List<VmExpr>) : VmExpr
    data class NewObject(val typeName: String, val args: List<VmExpr>, val anonymousBody: List<VmMethod>? = null) : VmExpr
    data class NewArray(val elemType: String, val dimExprs: List<VmExpr>, val initializer: ArrayInit?) : VmExpr
    data class ArrayInit(val elements: List<VmExpr>) : VmExpr
    data class Lambda(val params: List<String>, val body: VmStmt) : VmExpr
}

object LiveActivityVm {

    fun createSession(
        appName: String,
        packageName: String,
        files: List<ProjectFile>,
    ): LiveVmSession? {
        val strings = parseStrings(files.find { it.relativePath == "res/values/strings.xml" }?.content.orEmpty())
        val colors = parseColors(files.find { it.relativePath == "res/values/colors.xml" }?.content.orEmpty())
        val layouts = files.filter { it.relativePath.startsWith("res/layout/") && it.relativePath.endsWith(".xml") }
            .associate { it.relativePath.removePrefix("res/layout/") to it.content }

        val javaSources = mutableListOf<String>()
        files.filter { !it.isBinary && (it.relativePath.endsWith(".kt") || it.relativePath.endsWith(".java")) }
            .forEach { file ->
                if (file.relativePath.endsWith(".kt")) {
                    val res = runCatching {
                        KotlinToJavaTranspiler.transpile(file.content, packageName, file.fileName)
                    }.getOrNull()
                    if (res != null) javaSources.add(res.javaSource)
                } else {
                    val lowered = runCatching {
                        ModernJavaTranspiler.transpileToJava8(file.content).java8Source
                    }.getOrElse { file.content }
                    javaSources.add(lowered)
                }
            }

        if (javaSources.isEmpty()) return null

        val allClasses = linkedMapOf<String, VmClass>()
        javaSources.forEach { src ->
            runCatching {
                val parsed = JavaVmParser(src).parseFile()
                parsed.forEach { cls -> allClasses[cls.name] = cls }
            }
        }
        val mainCls = allClasses["MainActivity"]
            ?: allClasses.values.firstOrNull { cls -> cls.methods.any { it.name == "onCreate" } }
            ?: return null

        return LiveVmSession(
            appName = appName,
            packageName = packageName,
            strings = strings,
            colors = colors,
            layouts = layouts,
            classes = allClasses,
            mainClassName = mainCls.name,
        )
    }

    private fun parseStrings(xml: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        Regex("""<string\s+name="([^"]+)">([\s\S]*?)</string>""").findAll(xml).forEach { m ->
            map[m.groupValues[1].trim()] = m.groupValues[2].trim()
        }
        return map
    }

    private fun parseColors(xml: String): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        Regex("""<color\s+name="([^"]+)">([^<]+)</color>""").findAll(xml).forEach { m ->
            val hex = m.groupValues[2].trim().removePrefix("#")
            val c = when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                8 -> hex.toLongOrNull(16)?.toInt()
                else -> null
            }
            if (c != null) map[m.groupValues[1].trim()] = c
        }
        return map
    }
}

// --- Lexer & Parser ---

private enum class TokKind {
    IDENT, INT, FLOAT, DOUBLE, LONG, STRING, CHAR, SYMBOL, EOF
}

private data class Tok(val kind: TokKind, val text: String)

private class JavaVmParser(src: String) {
    private val tokens: List<Tok> = tokenize(src)
    private var pos = 0
    private var parseDepth = 0
    private val maxParseDepth = 128
    private val parsedClasses = mutableListOf<VmClass>()

    fun parseFile(): List<VmClass> {
        while (!at(TokKind.EOF)) {
            if (peek().text == "package" || peek().text == "import") {
                while (!at(TokKind.EOF) && peek().text != ";") advance()
                match(";")
                continue
            }
            if (peek().text == "@") {
                skipAnnotation()
                continue
            }
            if (isClassStart()) {
                parseClass()
            } else {
                advance()
            }
        }
        return parsedClasses
    }

    private fun skipModifiersAndAnnotations() {
        while (!at(TokKind.EOF)) {
            if (peek().text == "@") {
                skipAnnotation()
            } else if (peek().text in MODIFIERS) {
                advance()
            } else {
                break
            }
        }
    }

    private fun skipAnnotation() {
        match("@")
        if (at(TokKind.IDENT)) advance()
        while (match(".")) {
            if (at(TokKind.IDENT)) advance()
        }
        if (match("(")) {
            var depth = 1
            while (!at(TokKind.EOF) && depth > 0) {
                val t = advance().text
                if (t == "(") depth++
                else if (t == ")") depth--
            }
        }
    }

    private fun isClassStart(): Boolean {
        var i = pos
        while (i < tokens.size) {
            val t = tokens[i].text
            if (t == "@") {
                i++
                if (i < tokens.size && tokens[i].kind == TokKind.IDENT) i++
                while (i < tokens.size && tokens[i].text == ".") {
                    i += 2
                }
                if (i < tokens.size && tokens[i].text == "(") {
                    var d = 1
                    i++
                    while (i < tokens.size && d > 0) {
                        if (tokens[i].text == "(") d++ else if (tokens[i].text == ")") d--
                        i++
                    }
                }
                continue
            }
            if (t in MODIFIERS) {
                i++
                continue
            }
            return t == "class" || t == "interface" || t == "enum"
        }
        return false
    }

    private fun parseClass(): VmClass {
        skipModifiersAndAnnotations()
        if (peek().text == "class" || peek().text == "interface" || peek().text == "enum") advance()
        val className = if (at(TokKind.IDENT)) advance().text else "Anonymous"
        if (peek().text == "<") skipBalanced("<", ">")
        var superName: String? = null
        if (match("extends")) {
            superName = parseTypeName().substringAfterLast('.')
        }
        if (match("implements")) {
            parseTypeName()
            while (match(",")) parseTypeName()
        }
        val fields = mutableListOf<VmField>()
        val ctors = mutableListOf<VmMethod>()
        val methods = mutableListOf<VmMethod>()

        match("{")
        while (!at(TokKind.EOF) && peek().text != "}") {
            if (match(";")) continue
            if (isClassStart()) {
                parseClass()
                continue
            }
            if (peek().text == "static" && tokens.getOrNull(pos + 1)?.text == "{") {
                advance()
                parseBlock()
                continue
            }
            if (peek().text == "{") {
                parseBlock()
                continue
            }
            var isStatic = false
            while (peek().text in MODIFIERS || peek().text == "@") {
                if (peek().text == "@") skipAnnotation()
                else {
                    if (peek().text == "static") isStatic = true
                    advance()
                }
            }
            if (peek().text == "<") skipBalanced("<", ">")

            // Constructor check: Ident(...)
            if (at(TokKind.IDENT) && peek().text == className && tokens.getOrNull(pos + 1)?.text == "(") {
                advance()
                val params = parseParamList()
                if (match("throws")) {
                    parseTypeName(); while (match(",")) parseTypeName()
                }
                val body = if (peek().text == "{") parseBlock() else { match(";"); emptyList() }
                ctors.add(VmMethod(className, className, params, body, false))
                continue
            }

            val memberType = parseTypeName()
            if (!at(TokKind.IDENT)) {
                while (!at(TokKind.EOF) && peek().text != ";" && peek().text != "}") advance()
                match(";")
                continue
            }
            val memberName = advance().text
            if (peek().text == "(") {
                val params = parseParamList()
                if (match("throws")) {
                    parseTypeName(); while (match(",")) parseTypeName()
                }
                val body = if (peek().text == "{") parseBlock() else { match(";"); emptyList() }
                methods.add(VmMethod(memberName, memberType, params, body, isStatic))
            } else {
                // One or more field declarations separated by commas
                while (true) {
                    val init = if (match("=")) {
                        if (peek().text == "{") parseArrayInit() else parseExpr()
                    } else null
                    fields.add(VmField(memberType, memberName, isStatic, init))
                    if (!match(",")) break
                    if (at(TokKind.IDENT)) {
                        val nextName = advance().text
                        val nextInit = if (match("=")) {
                            if (peek().text == "{") parseArrayInit() else parseExpr()
                        } else null
                        fields.add(VmField(memberType, nextName, isStatic, nextInit))
                    }
                    break
                }
                match(";")
            }
        }
        match("}")
        val vmCls = VmClass(className, superName, fields, ctors, methods)
        parsedClasses.add(vmCls)
        return vmCls
    }

    private fun parseParamList(): List<Pair<String, String>> {
        val params = mutableListOf<Pair<String, String>>()
        match("(")
        while (!at(TokKind.EOF) && peek().text != ")") {
            while (peek().text in MODIFIERS || peek().text == "@") {
                if (peek().text == "@") skipAnnotation() else advance()
            }
            val pType = parseTypeName()
            match("...")
            val pName = if (at(TokKind.IDENT)) advance().text else "p${params.size}"
            while (match("[")) match("]")
            params.add(pType to pName)
            if (!match(",")) break
        }
        match(")")
        return params
    }

    private fun parseTypeName(): String {
        val sb = StringBuilder()
        if (at(TokKind.IDENT)) sb.append(advance().text)
        while (match(".")) {
            sb.append('.')
            if (at(TokKind.IDENT)) sb.append(advance().text)
        }
        if (peek().text == "<") skipBalanced("<", ">")
        while (match("[")) {
            match("]")
            sb.append("[]")
        }
        return sb.toString().ifBlank { "Object" }
    }

    private fun parseBlock(): List<VmStmt> {
        val out = mutableListOf<VmStmt>()
        match("{")
        if (parseDepth >= maxParseDepth) {
            skipBalancedFallback("}")
            return out
        }
        parseDepth++
        try {
            while (!at(TokKind.EOF) && peek().text != "}") {
                val before = pos
                out.add(parseStmt())
                if (pos == before) advance()
            }
            match("}")
        } finally {
            parseDepth--
        }
        return out
    }

    private fun parseStmt(): VmStmt {
        if (parseDepth >= maxParseDepth) {
            if (!at(TokKind.EOF) && peek().text != "}") advance()
            return VmStmt.Empty
        }
        parseDepth++
        try {
        return when (peek().text) {
            "{" -> VmStmt.Block(parseBlock())
            ";" -> { advance(); VmStmt.Empty }
            "return" -> {
                advance()
                val e = if (peek().text != ";") parseExpr() else null
                match(";")
                VmStmt.Return(e)
            }
            "break" -> { advance(); match(";"); VmStmt.Break }
            "continue" -> { advance(); match(";"); VmStmt.Continue }
            "if" -> {
                advance()
                match("(")
                val cond = parseExpr()
                match(")")
                val thenS = parseStmt()
                val elseS = if (match("else")) parseStmt() else null
                VmStmt.If(cond, thenS, elseS)
            }
            "while" -> {
                advance()
                match("(")
                val cond = parseExpr()
                match(")")
                VmStmt.While(cond, parseStmt())
            }
            "do" -> {
                advance()
                val body = parseStmt()
                match("while")
                match("(")
                val cond = parseExpr()
                match(")")
                match(";")
                VmStmt.DoWhile(body, cond)
            }
            "for" -> parseForStmt()
            "switch" -> parseSwitchStmt()
            "try" -> {
                advance()
                val tryB = parseStmt()
                var catchVar: String? = null
                var catchB: VmStmt? = null
                while (match("catch")) {
                    match("(")
                    parseTypeName()
                    if (at(TokKind.IDENT)) catchVar = advance().text
                    match(")")
                    catchB = parseStmt()
                }
                if (match("finally")) parseStmt()
                VmStmt.TryCatch(tryB, catchVar, catchB)
            }
            "throw" -> {
                advance()
                if (peek().text != ";") parseExpr()
                match(";")
                VmStmt.Empty
            }
            else -> {
                if (looksLikeVarDecl()) {
                    parseVarDeclStmt(consumeSemi = true)
                } else {
                    val e = parseExpr()
                    match(";")
                    VmStmt.ExprStmt(e)
                }
            }
        }
        } finally {
            parseDepth--
        }
    }

    private fun parseForStmt(): VmStmt {
        advance()
        match("(")
        // Check enhanced for: for (Type name : iterable)
        val saved = pos
        if (looksLikeForEach()) {
            while (peek().text in MODIFIERS) advance()
            parseTypeName()
            val vName = advance().text
            match(":")
            val iterable = parseExpr()
            match(")")
            val body = parseStmt()
            return VmStmt.ForEach(vName, iterable, body)
        }
        pos = saved
        val init: VmStmt? = when {
            peek().text == ";" -> { advance(); null }
            looksLikeVarDecl() -> parseVarDeclStmt(consumeSemi = true)
            else -> {
                val e = parseExpr()
                match(";")
                VmStmt.ExprStmt(e)
            }
        }
        val cond = if (peek().text != ";") parseExpr() else null
        match(";")
        val updates = mutableListOf<VmExpr>()
        while (!at(TokKind.EOF) && peek().text != ")") {
            updates.add(parseExpr())
            if (!match(",")) break
        }
        match(")")
        val body = parseStmt()
        return VmStmt.For(init, cond, updates, body)
    }

    private fun looksLikeForEach(): Boolean {
        var i = pos
        while (i < tokens.size && tokens[i].text in MODIFIERS) i++
        if (i >= tokens.size || tokens[i].kind != TokKind.IDENT) return false
        i++
        while (i < tokens.size && tokens[i].text == ".") i += 2
        if (i < tokens.size && tokens[i].text == "<") {
            var d = 1; i++
            while (i < tokens.size && d > 0) {
                if (tokens[i].text == "<") d++ else if (tokens[i].text == ">") d--
                i++
            }
        }
        while (i + 1 < tokens.size && tokens[i].text == "[" && tokens[i + 1].text == "]") i += 2
        if (i < tokens.size && tokens[i].kind == TokKind.IDENT && tokens.getOrNull(i + 1)?.text == ":") {
            return true
        }
        return false
    }

    private fun parseSwitchStmt(): VmStmt {
        advance()
        match("(")
        val target = parseExpr()
        match(")")
        match("{")
        val arms = mutableListOf<VmStmt.SwitchArm>()
        while (!at(TokKind.EOF) && peek().text != "}") {
            val labels = mutableListOf<VmExpr>()
            while (peek().text == "case" || peek().text == "default") {
                if (match("case")) {
                    labels.add(parseExpr())
                    while (match(",")) labels.add(parseExpr())
                    match(":")
                } else {
                    advance()
                    match(":")
                    labels.clear()
                    break
                }
            }
            val stmts = mutableListOf<VmStmt>()
            while (!at(TokKind.EOF) && peek().text != "}" && peek().text != "case" && peek().text != "default") {
                stmts.add(parseStmt())
            }
            arms.add(VmStmt.SwitchArm(labels, stmts))
        }
        match("}")
        return VmStmt.Switch(target, arms)
    }

    private fun looksLikeVarDecl(): Boolean {
        var i = pos
        while (i < tokens.size && tokens[i].text in MODIFIERS) i++
        if (i >= tokens.size || tokens[i].kind != TokKind.IDENT) return false
        val first = tokens[i].text
        if (first in KEYWORDS_NON_TYPE) return false
        i++
        while (i < tokens.size && tokens[i].text == ".") {
            i++
            if (i < tokens.size && tokens[i].kind == TokKind.IDENT) i++ else return false
        }
        if (i < tokens.size && tokens[i].text == "<") {
            var d = 1; i++
            while (i < tokens.size && d > 0) {
                if (tokens[i].text == "<") d++ else if (tokens[i].text == ">") d--
                i++
            }
        }
        while (i + 1 < tokens.size && tokens[i].text == "[" && tokens[i + 1].text == "]") i += 2
        if (i >= tokens.size || tokens[i].kind != TokKind.IDENT) return false
        val varName = tokens[i].text
        if (varName in KEYWORDS_ALL) return false
        val after = tokens.getOrNull(i + 1)?.text
        return after == "=" || after == ";" || after == ","
    }

    private fun parseVarDeclStmt(consumeSemi: Boolean): VmStmt.VarDecl {
        while (peek().text in MODIFIERS) advance()
        val type = parseTypeName()
        val decls = mutableListOf<Pair<String, VmExpr?>>()
        while (true) {
            val name = if (at(TokKind.IDENT)) advance().text else break
            while (match("[")) match("]")
            val init = if (match("=")) {
                if (peek().text == "{") parseArrayInit() else parseExpr()
            } else null
            decls.add(name to init)
            if (!match(",")) break
        }
        if (consumeSemi) match(";")
        return VmStmt.VarDecl(type, decls)
    }

    private fun parseExpr(): VmExpr {
        if (parseDepth >= maxParseDepth) {
            if (!at(TokKind.EOF)) advance()
            return VmExpr.Literal(null)
        }
        parseDepth++
        return try {
            parseAssignment()
        } finally {
            parseDepth--
        }
    }

    private fun parseAssignment(): VmExpr {
        val lhs = parseTernary()
        val op = peek().text
        if (op in ASSIGN_OPS) {
            advance()
            val rhs = parseAssignment()
            return VmExpr.Assign(lhs, op, rhs)
        }
        return lhs
    }

    private fun parseTernary(): VmExpr {
        val cond = parseOr()
        if (match("?")) {
            val t = parseExpr()
            match(":")
            val e = parseTernary()
            return VmExpr.Ternary(cond, t, e)
        }
        return cond
    }

    private fun parseOr(): VmExpr {
        var left = parseAnd()
        while (match("||")) left = VmExpr.Binary("||", left, parseAnd())
        return left
    }

    private fun parseAnd(): VmExpr {
        var left = parseBitOr()
        while (match("&&")) left = VmExpr.Binary("&&", left, parseBitOr())
        return left
    }

    private fun parseBitOr(): VmExpr {
        var left = parseBitXor()
        while (match("|")) left = VmExpr.Binary("|", left, parseBitXor())
        return left
    }

    private fun parseBitXor(): VmExpr {
        var left = parseBitAnd()
        while (match("^")) left = VmExpr.Binary("^", left, parseBitAnd())
        return left
    }

    private fun parseBitAnd(): VmExpr {
        var left = parseEquality()
        while (match("&")) left = VmExpr.Binary("&", left, parseEquality())
        return left
    }

    private fun parseEquality(): VmExpr {
        var left = parseRelational()
        while (peek().text == "==" || peek().text == "!=") {
            val op = advance().text
            left = VmExpr.Binary(op, left, parseRelational())
        }
        return left
    }

    private fun parseRelational(): VmExpr {
        var left = parseShift()
        while (true) {
            val op = peek().text
            if (op == "<" || op == "<=" || op == ">" || op == ">=") {
                advance()
                left = VmExpr.Binary(op, left, parseShift())
            } else if (match("instanceof")) {
                val tName = parseTypeName().substringAfterLast('.')
                left = VmExpr.InstanceOf(left, tName)
            } else {
                break
            }
        }
        return left
    }

    private fun parseShift(): VmExpr {
        var left = parseAdd()
        while (peek().text in listOf("<<", ">>", ">>>")) {
            val op = advance().text
            left = VmExpr.Binary(op, left, parseAdd())
        }
        return left
    }

    private fun parseAdd(): VmExpr {
        var left = parseMul()
        while (peek().text == "+" || peek().text == "-") {
            val op = advance().text
            left = VmExpr.Binary(op, left, parseMul())
        }
        return left
    }

    private fun parseMul(): VmExpr {
        var left = parseUnary()
        while (peek().text == "*" || peek().text == "/" || peek().text == "%") {
            val op = advance().text
            left = VmExpr.Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): VmExpr {
        val op = peek().text
        if (op == "!" || op == "-" || op == "+" || op == "~" || op == "++" || op == "--") {
            advance()
            return VmExpr.Unary(op, parseUnary())
        }
        if (peek().text == "(" && looksLikeCast()) {
            advance()
            val targetType = parseTypeName().substringAfterLast('.')
            match(")")
            return VmExpr.Cast(targetType, parseUnary())
        }
        return parsePostfix()
    }

    private fun looksLikeCast(): Boolean {
        if (peek().text != "(") return false
        var i = pos + 1
        if (i >= tokens.size || tokens[i].kind != TokKind.IDENT) return false
        val first = tokens[i].text
        if (first in PRIMITIVE_TYPES) {
            i++
            while (i + 1 < tokens.size && tokens[i].text == "[" && tokens[i + 1].text == "]") i += 2
            return tokens.getOrNull(i)?.text == ")"
        }
        // Simple Reference cast like (TextView) findViewById(...)
        while (i < tokens.size && tokens[i].text == ".") i += 2
        if (i < tokens.size && tokens[i].kind == TokKind.IDENT) i++ else return false
        while (i + 1 < tokens.size && tokens[i].text == "[" && tokens[i + 1].text == "]") i += 2
        if (tokens.getOrNull(i)?.text != ")") return false
        val afterClose = tokens.getOrNull(i + 1) ?: return false
        return afterClose.kind == TokKind.IDENT || afterClose.text == "(" || afterClose.text == "this" || afterClose.kind == TokKind.INT
    }

    private fun parsePostfix(): VmExpr {
        var expr = parsePrimary()
        while (true) {
            when (peek().text) {
                "." -> {
                    advance()
                    val name = if (at(TokKind.IDENT)) advance().text else ""
                    if (peek().text == "(") {
                        val args = parseArgList()
                        expr = VmExpr.Call(expr, name, args)
                    } else {
                        expr = VmExpr.FieldAccess(expr, name)
                    }
                }
                "(" -> {
                    val args = parseArgList()
                    val callName = if (expr is VmExpr.Ident) expr.name else "invoke"
                    expr = VmExpr.Call(null, callName, args)
                }
                "[" -> {
                    advance()
                    val idx = parseExpr()
                    match("]")
                    expr = VmExpr.IndexAccess(expr, idx)
                }
                "++", "--" -> {
                    val op = advance().text
                    expr = VmExpr.Postfix(expr, op)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): VmExpr {
        val tok = peek()
        // Lambda? (params) -> body
        if (tok.text == "(" && looksLikeLambda()) {
            return parseLambda()
        }
        if (tok.kind == TokKind.IDENT && tokens.getOrNull(pos + 1)?.text == "->") {
            val pName = advance().text
            advance() // ->
            val body = if (peek().text == "{") VmStmt.Block(parseBlock()) else VmStmt.Return(parseExpr())
            return VmExpr.Lambda(listOf(pName), body)
        }
        return when {
            tok.kind == TokKind.INT -> { advance(); VmExpr.Literal(parseIntToken(tok.text)) }
            tok.kind == TokKind.LONG -> { advance(); VmExpr.Literal(parseLongToken(tok.text)) }
            tok.kind == TokKind.FLOAT -> { advance(); VmExpr.Literal(tok.text.dropLast(1).toFloatOrNull() ?: 0f) }
            tok.kind == TokKind.DOUBLE -> { advance(); VmExpr.Literal(tok.text.removeSuffix("d").removeSuffix("D").toDoubleOrNull() ?: 0.0) }
            tok.kind == TokKind.STRING -> { advance(); VmExpr.Literal(tok.text) }
            tok.kind == TokKind.CHAR -> { advance(); VmExpr.Literal(tok.text.firstOrNull() ?: '\u0000') }
            tok.text == "true" -> { advance(); VmExpr.Literal(true) }
            tok.text == "false" -> { advance(); VmExpr.Literal(false) }
            tok.text == "null" -> { advance(); VmExpr.Literal(null) }
            tok.text == "this" -> { advance(); VmExpr.This }
            tok.text == "new" -> parseNewExpr()
            tok.text == "(" -> {
                advance()
                val e = parseExpr()
                match(")")
                e
            }
            tok.kind == TokKind.IDENT -> {
                advance()
                VmExpr.Ident(tok.text)
            }
            else -> {
                advance()
                VmExpr.Literal(null)
            }
        }
    }

    private fun looksLikeLambda(): Boolean {
        var i = pos + 1
        var depth = 1
        while (i < tokens.size && depth > 0) {
            if (tokens[i].text == "(") depth++
            else if (tokens[i].text == ")") depth--
            i++
        }
        return tokens.getOrNull(i)?.text == "->"
    }

    private fun parseLambda(): VmExpr.Lambda {
        match("(")
        val params = mutableListOf<String>()
        while (!at(TokKind.EOF) && peek().text != ")") {
            val first = if (at(TokKind.IDENT)) advance().text else break
            if (at(TokKind.IDENT) && peek().text != "," && peek().text != ")") {
                params.add(advance().text)
            } else {
                params.add(first)
            }
            if (!match(",")) break
        }
        match(")")
        match("->")
        val body = if (peek().text == "{") VmStmt.Block(parseBlock()) else VmStmt.Return(parseExpr())
        return VmExpr.Lambda(params, body)
    }

    private fun parseNewExpr(): VmExpr {
        match("new")
        val typeSb = StringBuilder()
        if (at(TokKind.IDENT)) typeSb.append(advance().text)
        while (match(".")) {
            typeSb.append('.')
            if (at(TokKind.IDENT)) typeSb.append(advance().text)
        }
        if (peek().text == "<") skipBalanced("<", ">")
        val typeName = typeSb.toString()

        if (peek().text == "[") {
            val dims = mutableListOf<VmExpr>()
            while (match("[")) {
                if (peek().text != "]") dims.add(parseExpr())
                match("]")
            }
            val init = if (peek().text == "{") parseArrayInit() else null
            return VmExpr.NewArray(typeName, dims, init)
        }
        val args = if (peek().text == "(") parseArgList() else emptyList()
        var anonMethods: List<VmMethod>? = null
        if (peek().text == "{") {
            val dummyCls = parseAnonymousClassBody()
            anonMethods = dummyCls
        }
        return VmExpr.NewObject(typeName, args, anonMethods)
    }

    private fun parseAnonymousClassBody(): List<VmMethod> {
        val methods = mutableListOf<VmMethod>()
        match("{")
        while (!at(TokKind.EOF) && peek().text != "}") {
            if (match(";")) continue
            while (peek().text in MODIFIERS || peek().text == "@") {
                if (peek().text == "@") skipAnnotation() else advance()
            }
            val rType = parseTypeName()
            if (!at(TokKind.IDENT)) {
                while (!at(TokKind.EOF) && peek().text != ";" && peek().text != "}") advance()
                match(";")
                continue
            }
            val mName = advance().text
            if (peek().text == "(") {
                val params = parseParamList()
                val body = if (peek().text == "{") parseBlock() else { match(";"); emptyList() }
                methods.add(VmMethod(mName, rType, params, body, false))
            } else {
                while (!at(TokKind.EOF) && peek().text != ";" && peek().text != "}") advance()
                match(";")
            }
        }
        match("}")
        return methods
    }

    private fun parseArrayInit(): VmExpr.ArrayInit {
        match("{")
        val elems = mutableListOf<VmExpr>()
        while (!at(TokKind.EOF) && peek().text != "}") {
            val el = if (peek().text == "{") parseArrayInit() else parseExpr()
            elems.add(el)
            if (!match(",")) break
        }
        match("}")
        return VmExpr.ArrayInit(elems)
    }

    private fun parseArgList(): List<VmExpr> {
        val args = mutableListOf<VmExpr>()
        match("(")
        while (!at(TokKind.EOF) && peek().text != ")") {
            args.add(parseExpr())
            if (!match(",")) break
        }
        match(")")
        return args
    }

    private fun parseIntToken(t: String): Int {
        val clean = t.replace("_", "")
        return if (clean.startsWith("0x", ignoreCase = true)) {
            clean.substring(2).toLongOrNull(16)?.toInt() ?: 0
        } else {
            clean.toIntOrNull() ?: 0
        }
    }

    private fun parseLongToken(t: String): Long {
        val clean = t.dropLast(1).replace("_", "")
        return if (clean.startsWith("0x", ignoreCase = true)) {
            clean.substring(2).toLongOrNull(16) ?: 0L
        } else {
            clean.toLongOrNull() ?: 0L
        }
    }

    private fun skipBalanced(open: String, close: String) {
        var depth = 0
        while (!at(TokKind.EOF)) {
            val t = advance().text
            if (t == open) depth++
            else if (t == close) {
                depth--
                if (depth == 0) break
            }
        }
    }

    private fun skipBalancedFallback(close: String) {
        var depth = 1
        while (!at(TokKind.EOF) && depth > 0) {
            val t = advance().text
            if (t == "{") depth++
            else if (t == close) depth--
        }
    }

    private fun peek(): Tok = tokens.getOrElse(pos) { Tok(TokKind.EOF, "") }
    private fun at(kind: TokKind): Boolean = peek().kind == kind
    private fun advance(): Tok = tokens.getOrElse(pos++) { Tok(TokKind.EOF, "") }
    private fun match(sym: String): Boolean {
        if (peek().text == sym) {
            pos++
            return true
        }
        return false
    }

    companion object {
        private val MODIFIERS = setOf(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "volatile", "transient", "native", "default", "strictfp",
        )
        private val PRIMITIVE_TYPES = setOf("int", "long", "float", "double", "boolean", "char", "byte", "short")
        private val KEYWORDS_NON_TYPE = setOf(
            "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "try", "catch", "finally", "throw", "new", "this", "super",
            "true", "false", "null", "instanceof",
        )
        private val KEYWORDS_ALL = MODIFIERS + KEYWORDS_NON_TYPE
        private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=")

        private fun tokenize(src: String): List<Tok> {
            val out = mutableListOf<Tok>()
            var i = 0
            val n = src.length
            while (i < n) {
                val c = src[i]
                if (c.isWhitespace()) {
                    i++; continue
                }
                if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                    continue
                }
                if (c == '"') {
                    val sb = StringBuilder()
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < n) {
                            when (src[i + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                '\\' -> sb.append('\\')
                                '"' -> sb.append('"')
                                else -> sb.append(src[i + 1])
                            }
                            i += 2
                        } else {
                            sb.append(src[i]); i++
                        }
                    }
                    i++
                    out.add(Tok(TokKind.STRING, sb.toString()))
                    continue
                }
                if (c == '\'') {
                    i++
                    val ch = if (i < n && src[i] == '\\' && i + 1 < n) {
                        val esc = src[i + 1]; i += 2; esc
                    } else if (i < n) {
                        val raw = src[i]; i++; raw
                    } else '\u0000'
                    if (i < n && src[i] == '\'') i++
                    out.add(Tok(TokKind.CHAR, ch.toString()))
                    continue
                }
                if (c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit())) {
                    val start = i
                    if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                        i += 2
                        while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F' || src[i] == '_')) i++
                        val isLong = i < n && (src[i] == 'L' || src[i] == 'l')
                        if (isLong) i++
                        out.add(Tok(if (isLong) TokKind.LONG else TokKind.INT, src.substring(start, i)))
                        continue
                    }
                    var isFloatDot = false
                    while (i < n && (src[i].isDigit() || src[i] == '_' || (src[i] == '.' && i + 1 < n && src[i + 1].isDigit()))) {
                        if (src[i] == '.') isFloatDot = true
                        i++
                    }
                    if (i < n && (src[i] == 'f' || src[i] == 'F')) {
                        i++; out.add(Tok(TokKind.FLOAT, src.substring(start, i)))
                    } else if (i < n && (src[i] == 'd' || src[i] == 'D')) {
                        i++; out.add(Tok(TokKind.DOUBLE, src.substring(start, i)))
                    } else if (i < n && (src[i] == 'L' || src[i] == 'l')) {
                        i++; out.add(Tok(TokKind.LONG, src.substring(start, i)))
                    } else {
                        out.add(Tok(if (isFloatDot) TokKind.DOUBLE else TokKind.INT, src.substring(start, i)))
                    }
                    continue
                }
                if (c.isLetter() || c == '_' || c == '$') {
                    val start = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                    out.add(Tok(TokKind.IDENT, src.substring(start, i)))
                    continue
                }
                // Multi-char operators
                val three = if (i + 2 < n) src.substring(i, i + 3) else ""
                if (three == ">>>" || three == "...") {
                    out.add(Tok(TokKind.SYMBOL, three)); i += 3; continue
                }
                val two = if (i + 1 < n) src.substring(i, i + 2) else ""
                if (two in TWO_CHAR_OPS) {
                    out.add(Tok(TokKind.SYMBOL, two)); i += 2; continue
                }
                out.add(Tok(TokKind.SYMBOL, c.toString()))
                i++
            }
            out.add(Tok(TokKind.EOF, ""))
            return out
        }

        private val TWO_CHAR_OPS = setOf(
            "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "->", "<<", ">>",
        )
    }
}
