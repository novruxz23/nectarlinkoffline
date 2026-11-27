// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.nectarlink.android.R
import app.nectarlink.core.MirrorInputEvent
import app.nectarlink.core.TouchPhase
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One shared PC window, floating over whatever app is open: only that window's
 * picture, scaled to fit (never stretched), with a header to move it, a corner to resize
 * it (or a pinch), a bubble it collapses to, and touch that goes back to the window on
 * the PC. It never takes the focus from the app below, except while its keyboard is up.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class WindowOverlayView(
    context: Context,
    val key: WindowOverlays.Key,
    val app: String,
    title: String,
    private var srcW: Int,
    private var srcH: Int,
    private var viewOnly: Boolean,
    private var placement: OverlayPlacement,
    private val host: Host,
) : FrameLayout(context) {
    interface Host {
        fun sendInput(view: WindowOverlayView, input: MirrorInputEvent)
        fun requestKeyframe(view: WindowOverlayView)
        fun closeByUser(view: WindowOverlayView)
        fun remember(view: WindowOverlayView, placement: OverlayPlacement)
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val params = WindowManager.LayoutParams(
        placement.width,
        placement.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never the focus (the app below keeps it), and touches outside go to that app.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = placement.x
        y = placement.y
        setTitle("NectarLink window")
    }

    private var quarterTurns = 0
    private var collapsed = placement.collapsed
    private var typing = false
    private var paused = false
    private var attached = false
    private var gotPicture = false

    private val decoder = OverlayVideoDecoder(
        needKeyframe = { host.requestKeyframe(this) },
        firstPicture = { main.post { gotPicture = true; refreshStatus() } },
    )

    private val video = TextureView(context)
    private val stage = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setBackgroundColor(0xB3000000.toInt())
        visibility = GONE
    }
    private val titleView = TextView(context).apply {
        text = title
        setTextColor(Color.WHITE)
        textSize = 12f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(10), 0, dp(4), 0)
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = context.getString(R.string.window_overlay_move)
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xE6202124.toInt())
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = TextView(context).apply {
        text = app.take(1).uppercase()
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setBackgroundColor(0xF2202124.toInt())
        contentDescription = context.getString(R.string.window_overlay_expand)
        visibility = GONE
    }
    private val resizeHandle = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            strokeWidth = 2f * density
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 1..3) {
                val o = i * w / 4f
                canvas.drawLine(w - o, h - 3f * density, w - 3f * density, h - o, paint)
            }
        }
    }.apply { contentDescription = context.getString(R.string.window_overlay_resize) }
    private val typer = TypingField(context)

    private fun headerButton(glyph: String, description: Int, onTap: () -> Unit) = TextView(context).apply {
        text = glyph
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        minWidth = dp(36)
        contentDescription = context.getString(description)
        setOnClickListener { onTap() }
    }

    private val keyboardButton = headerButton("⌨", R.string.window_overlay_keyboard) { setTyping(!typing) }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, if (collapsed) view.width / 2f else 12f * density)
            }
        }
        elevation = 8f * density
        header.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(keyboardButton, LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        header.addView(headerButton("⟳", R.string.window_overlay_rotate) { turn() }, LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        header.addView(headerButton("—", R.string.window_overlay_collapse) { setCollapsed(true) }, LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        header.addView(headerButton("✕", R.string.window_overlay_close) { host.closeByUser(this) }, LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        content.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_DP)))
        stage.addView(video)
        stage.addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(stage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(resizeHandle, LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))
        addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(typer, LayoutParams(1, 1))

        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                decoder.setSurface(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                decoder.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        stage.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) layoutPicture()
        }
        stage.setOnTouchListener { _, e -> onStageTouch(e) }
        header.setOnTouchListener(MoveListener(tapToExpand = false))
        bubble.setOnTouchListener(MoveListener(tapToExpand = true))
        resizeHandle.setOnTouchListener { _, e -> onResizeTouch(e) }
        refreshStatus()
        applyCollapsed()
    }

    // ---- Window ----

    /** Puts the overlay on the screen (main thread). False if Android refuses. */
    fun attach(): Boolean {
        return try {
            wm.addView(this, params)
            attached = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun detach() {
        decoder.release()
        if (typing) hideKeyboard()
        if (attached) {
            attached = false
            runCatching { wm.removeViewImmediate(this) }
        }
    }

    private fun updateWindow() {
        if (!attached) return
        val bubbleSize = dp(BUBBLE_DP)
        params.x = placement.x
        params.y = placement.y
        params.width = if (collapsed) bubbleSize else placement.width
        params.height = if (collapsed) bubbleSize else placement.height
        runCatching { wm.updateViewLayout(this, params) }
        invalidateOutline()
    }

    private fun screen(): Pair<Int, Int> = WindowOverlays.screenSize(context)

    private fun move(x: Int, y: Int) {
        val (sw, sh) = screen()
        val w = if (collapsed) dp(BUBBLE_DP) else placement.width
        val h = if (collapsed) dp(BUBBLE_DP) else placement.height
        placement = placement.copy(x = x.coerceIn(0, maxOf(0, sw - w)), y = y.coerceIn(0, maxOf(0, sh - h)))
        updateWindow()
    }

    private fun resizeTo(next: OverlayPlacement) {
        val (sw, sh) = screen()
        placement = OverlayPlacement.clamp(next.copy(collapsed = collapsed), sw, sh, density)
        updateWindow()
    }

    private fun save() = host.remember(this, placement.copy(collapsed = collapsed))

    // ---- Stream ----

    fun onConfig(codec: String, w: Int, h: Int) {
        decoder.configure(codec, w, h)
        main.post {
            if (w != srcW || h != srcH) {
                srcW = w
                srcH = h
                layoutPicture()
            }
        }
    }

    fun onFrame(keyframe: Boolean, timeUs: Long, data: ByteArray) = decoder.packet(keyframe, timeUs, data)

    fun onWindowSize(w: Int, h: Int) = main.post {
        srcW = w
        srcH = h
        layoutPicture()
    }

    fun onPaused(value: Boolean) = main.post {
        paused = value
        refreshStatus()
        if (!value) host.requestKeyframe(this)
    }

    fun onViewOnly() = main.post {
        viewOnly = true
        refreshStatus()
    }

    fun onTitle(value: String) = main.post { titleView.text = value }

    private fun refreshStatus() {
        val text = when {
            paused -> context.getString(R.string.window_overlay_paused)
            !gotPicture -> context.getString(R.string.window_overlay_waiting)
            viewOnly -> context.getString(R.string.window_overlay_view_only)
            else -> null
        }
        status.text = text.orEmpty()
        // View-only only shows as a note on the corner of the picture, not over it.
        status.visibility = if (text == null) GONE else VISIBLE
        val note = text != null && !paused && gotPicture
        (status.layoutParams as LayoutParams).gravity = if (note) Gravity.BOTTOM or Gravity.START else Gravity.CENTER
        status.requestLayout()
    }

    /** The picture, as big as fits the stage with its shape kept, turned `quarterTurns`. */
    private fun layoutPicture() {
        val sw = stage.width.toFloat()
        val sh = stage.height.toFloat()
        val fit = OverlayCoords.fit(sw, sh, srcW, srcH, quarterTurns)
        if (fit.width <= 0f) return
        val odd = quarterTurns.mod(4) % 2 == 1
        val lp = LayoutParams(
            (if (odd) fit.height else fit.width).roundToInt(),
            (if (odd) fit.width else fit.height).roundToInt(),
            Gravity.CENTER,
        )
        video.layoutParams = lp
        video.rotation = 90f * quarterTurns.mod(4)
    }

    private fun turn() {
        quarterTurns = (quarterTurns + 1) % 4
        layoutPicture()
        // A window turned on its side wants an overlay of that shape.
        val aspect = OverlayCoords.shownAspect(srcW, srcH, quarterTurns)
        val stageH = (placement.width / aspect).roundToInt()
        resizeTo(placement.copy(height = stageH + dp(HEADER_DP)))
        save()
    }

    // ---- Collapse ----

    private fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        if (value && typing) setTyping(false)
        collapsed = value
        applyCollapsed()
        updateWindow()
        save()
    }

    private fun applyCollapsed() {
        content.visibility = if (collapsed) GONE else VISIBLE
        resizeHandle.visibility = if (collapsed) GONE else VISIBLE
        bubble.visibility = if (collapsed) VISIBLE else GONE
    }

    // ---- Touch on the window ----

    private var touching = false
    private var lastSent = 0L
    private var twoFinger = false
    private var twoMode = TwoMode.Undecided
    private var span0 = 0f
    private var centroid0 = 0f
    private var lastCentroidY = 0f
    private var scrollRest = 0f
    private var startPlacement = placement

    private enum class TwoMode { Undecided, Resize, Scroll }

    private fun toWindow(x: Float, y: Float, clamped: Boolean): Pair<Float, Float>? =
        if (clamped) {
            OverlayCoords.toWindowClamped(x, y, stage.width.toFloat(), stage.height.toFloat(), srcW, srcH, quarterTurns)
        } else {
            OverlayCoords.toWindow(x, y, stage.width.toFloat(), stage.height.toFloat(), srcW, srcH, quarterTurns)
        }

    private fun send(phase: TouchPhase, p: Pair<Float, Float>) =
        host.sendInput(this, MirrorInputEvent.Touch(phase, p.first, p.second))

    private fun onStageTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFinger = false
                if (viewOnly) {
                    refreshStatus()
                    return true
                }
                val p = toWindow(e.x, e.y, clamped = false) ?: return true
                touching = true
                lastSent = 0L
                send(TouchPhase.DOWN, p)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                if (touching) {
                    // The second finger is a scroll or a pinch, not a drag: let go of the first.
                    toWindow(e.getX(0), e.getY(0), clamped = true)?.let { send(TouchPhase.UP, it) }
                    touching = false
                }
                twoFinger = true
                twoMode = TwoMode.Undecided
                span0 = span(e)
                centroid0 = (e.getY(0) + e.getY(1)) / 2f
                lastCentroidY = centroid0
                scrollRest = 0f
                startPlacement = placement
            }
            MotionEvent.ACTION_MOVE -> when {
                touching && e.pointerCount == 1 -> {
                    val now = e.eventTime
                    if (now - lastSent >= MOVE_INTERVAL_MS) {
                        lastSent = now
                        toWindow(e.x, e.y, clamped = true)?.let { send(TouchPhase.MOVE, it) }
                    }
                }
                twoFinger && e.pointerCount >= 2 -> onTwoFingerMove(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touching) {
                    toWindow(e.x, e.y, clamped = true)?.let { send(TouchPhase.UP, it) }
                }
                if (twoFinger && twoMode == TwoMode.Resize) save()
                touching = false
                twoFinger = false
            }
        }
        return true
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun onTwoFingerMove(e: MotionEvent) {
        val centerY = (e.getY(0) + e.getY(1)) / 2f
        val spanNow = span(e)
        if (twoMode == TwoMode.Undecided) {
            val spread = abs(spanNow - span0)
            val slide = abs(centerY - centroid0)
            twoMode = when {
                spread > dp(PINCH_SLOP_DP) && spread > slide -> TwoMode.Resize
                slide > dp(SCROLL_SLOP_DP) -> TwoMode.Scroll
                else -> TwoMode.Undecided
            }
        }
        when (twoMode) {
            TwoMode.Resize -> if (span0 > 0f) {
                val (sw, sh) = screen()
                resizeTo(OverlayPlacement.scaled(startPlacement, spanNow / span0, sw, sh, density))
            }
            TwoMode.Scroll -> {
                if (viewOnly) {
                    refreshStatus()
                    return
                }
                scrollRest += OverlayCoords.scrollNotches(centerY - lastCentroidY, dp(NOTCH_DP).toFloat())
                lastCentroidY = centerY
                val whole = scrollRest.toInt()
                if (whole != 0) {
                    scrollRest -= whole
                    val at = toWindow((e.getX(0) + e.getX(1)) / 2f, centerY, clamped = true) ?: return
                    host.sendInput(this, MirrorInputEvent.Scroll(at.first, at.second, 0f, whole.toFloat()))
                }
            }
            TwoMode.Undecided -> Unit
        }
    }

    // ---- Move and resize ----

    private inner class MoveListener(private val tapToExpand: Boolean) : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = placement.x
                    startY = placement.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || hypot(dx, dy) > dp(TAP_SLOP_DP)) {
                        moved = true
                        move(startX + dx.roundToInt(), startY + dy.roundToInt())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) save() else if (tapToExpand) setCollapsed(false)
                }
            }
            return true
        }
    }

    private var resizeStartW = 0
    private var resizeStartX = 0f

    private fun onResizeTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartW = placement.width
                resizeStartX = e.rawX
            }
            MotionEvent.ACTION_MOVE -> {
                // The corner drags the width; the height follows so the picture keeps its shape.
                val w = resizeStartW + (e.rawX - resizeStartX).roundToInt()
                val aspect = OverlayCoords.shownAspect(srcW, srcH, quarterTurns)
                resizeTo(placement.copy(width = w, height = (w / aspect).roundToInt() + dp(HEADER_DP)))
            }
            MotionEvent.ACTION_UP -> save()
        }
        return true
    }

    // ---- Keyboard ----

    /** The keyboard into the window: the overlay takes the focus while it is up, and gives it back. */
    private fun setTyping(on: Boolean) {
        if (typing == on || (on && (viewOnly || collapsed))) {
            if (on && viewOnly) refreshStatus()
            return
        }
        typing = on
        params.flags = if (on) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(this, params) }
        keyboardButton.alpha = if (on) 0.6f else 1f
        if (on) {
            typer.setText("")
            typer.requestFocus()
            post { context.getSystemService(InputMethodManager::class.java).showSoftInput(typer, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        typer.clearFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Tapped away: the focus goes back to the app below.
        if (!hasWindowFocus && typing) setTyping(false)
    }

    /** A field nobody sees: what is typed into it is sent to the window as it changes. */
    private inner class TypingField(context: Context) : EditText(context) {
        private var before = ""

        init {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit

                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    val now = s?.toString().orEmpty()
                    val diff = TypingDiff.between(before, now)
                    before = now
                    repeat(diff.backspaces) { host.sendInput(this@WindowOverlayView, MirrorInputEvent.Key("backspace")) }
                    if (diff.added.isNotEmpty()) host.sendInput(this@WindowOverlayView, MirrorInputEvent.Text(diff.added))
                    // The field only keeps the last words, so it never grows without end.
                    if (now.length > MAX_TYPED) {
                        before = ""
                        setText("")
                    }
                }
            })
            setOnEditorActionListener { _, _, _ ->
                host.sendInput(this@WindowOverlayView, MirrorInputEvent.Key("enter"))
                true
            }
            setOnKeyListener { _, code, event ->
                // Backspace on an empty field changes no text, so it is sent as the key.
                if (code == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && text.isEmpty()) {
                    host.sendInput(this@WindowOverlayView, MirrorInputEvent.Key("backspace"))
                    true
                } else {
                    false
                }
            }
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            // A reset is not typing.
            if (text.isNullOrEmpty()) before = ""
            super.setText(text, type)
        }
    }

    private companion object {
        const val HEADER_DP = 36
        const val BUBBLE_DP = 56
        const val TAP_SLOP_DP = 8
        const val PINCH_SLOP_DP = 24
        const val SCROLL_SLOP_DP = 12
        const val NOTCH_DP = 40
        const val MOVE_INTERVAL_MS = 8L
        const val MAX_TYPED = 200
    }
}
