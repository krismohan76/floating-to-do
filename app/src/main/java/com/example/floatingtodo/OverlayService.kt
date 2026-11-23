package com.example.floatingtodo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import kotlin.math.abs

/** Six little dots, drawn by hand so it doesn't depend on any font. */
private class DragHandle(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = resources.displayMetrics.density * 1.8f
        val cx1 = width * 0.35f
        val cx2 = width * 0.65f
        for (i in 1..3) {
            val cy = height * i / 4f
            canvas.drawCircle(cx1, cy, r, paint)
            canvas.drawCircle(cx2, cy, r, paint)
        }
    }
}

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.floatingtodo.STOP"
        private const val CHANNEL_ID = "floating_todo"
        private const val GESTURE_NONE = 0
        private const val GESTURE_H = 1
        private const val GESTURE_V = 2
        private const val GESTURE_IGNORE = 3
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var rootView: FrameLayout
    private lateinit var card: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var slot: FrameLayout
    private lateinit var itemText: TextView
    private lateinit var viewRow: LinearLayout
    private lateinit var addRow: LinearLayout
    private lateinit var input: EditText

    private var attached = false
    private var minimized = false
    private var busy = false
    private var gesture = GESTURE_NONE
    private var downX = 0f
    private var downY = 0f
    private var yBeforeAdd = -1

    private val items = mutableListOf<String>()
    private var index = 0

    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val cardW: Int get() = dp(270)
    private val bubbleW: Int get() = dp(44)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "opacity" && attached) applyOpacity()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!attached) {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences("todo", MODE_PRIVATE)
            loadItems()
            try {
                buildOverlay()
                attached = true
                prefs.registerOnSharedPreferenceChangeListener(prefListener)
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) {
            clampToScreen()
            wm.updateViewLayout(rootView, params)
        }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (attached) {
            try {
                wm.removeView(rootView)
            } catch (e: Exception) {
            }
            attached = false
        }
        super.onDestroy()
    }

    // ---------- notification (keeps the service alive, has a Close action) ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating Todo", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val closeAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Close",
            stopIntent
        ).build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Todo is on")
            .setContentText("Tap Close to remove it")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(closeAction)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------- storage ----------

    private fun loadItems() {
        val arr = JSONArray(prefs.getString("items", "[]"))
        items.clear()
        for (i in 0 until arr.length()) items.add(arr.getString(i))
        index = prefs.getInt("index", 0)
        if (index !in items.indices) index = 0
    }

    private fun save() {
        prefs.edit()
            .putString("items", JSONArray(items).toString())
            .putInt("index", index)
            .apply()
    }

    private fun savePosition() {
        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun curW() = if (minimized) bubbleW else cardW
    private fun curH() = if (rootView.height > 0) rootView.height else dp(56)

    private fun clampToScreen() {
        val (sw, sh) = screenSize()
        params.x = params.x.coerceIn(0, maxOf(0, sw - curW()))
        params.y = params.y.coerceIn(0, maxOf(0, sh - curH()))
    }

    private fun applyOpacity() {
        rootView.alpha = prefs.getInt("opacity", 85).coerceIn(20, 100) / 100f
    }

    private fun button(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        setOnClickListener { onClick() }
    }

    // ---------- building the UI ----------

    private fun buildOverlay() {
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 34, 34, 34))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(6), dp(6), dp(8), dp(6))
        }

        // Drag handle (left side of the card): drag it to move the box anywhere.
        val handle = DragHandle(this)
        attachDrag(handle)

        itemText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        // The slot clips the text while it slides out of / into view.
        slot = FrameLayout(this).apply {
            addView(itemText, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        viewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle, LinearLayout.LayoutParams(dp(26), dp(40)))
            addView(slot, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button("\u2212", 24f) { deleteCurrent() })
            addView(button("+", 24f) { showAdd() })
        }

        input = EditText(this).apply {
            hint = "New item"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitAdd()
                    true
                } else false
            }
        }
        addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button("\u2713", 20f) { commitAdd() })
            addView(button("\u2715", 18f) { hideAdd() })
        }

        card.addView(viewRow)
        card.addView(addRow)

        // Minimised form: a small round bubble. Tap it to bring the card back.
        bubble = TextView(this).apply {
            text = "\u2630"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 34, 34, 34))
            }
            visibility = View.GONE
        }
        attachDrag(bubble) { restore() }

        rootView = FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(cardW, WRAP))
            addView(bubble, FrameLayout.LayoutParams(bubbleW, bubbleW))
        }

        setupGestures()

        val (sw, _) = screenSize()
        params = WindowManager.LayoutParams(
            WRAP,
            WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", sw - dp(270) - dp(8))
            y = prefs.getInt("y", dp(80))
        }

        refresh()
        applyOpacity()
        clampToScreen()
        wm.addView(rootView, params)
    }

    // ---------- moving the box ----------

    private fun attachDrag(target: View, onTap: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        target.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampToScreen()
                        wm.updateViewLayout(rootView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        if (!minimized) savePosition()
                    } else {
                        onTap?.invoke()
                    }
                }
            }
            true
        }
    }

    // ---------- swipe gestures on the card ----------

    private fun setupGestures() {
        card.setOnTouchListener { _, e ->
            if (addRow.visibility == View.VISIBLE) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    gesture = if (busy) GESTURE_IGNORE else GESTURE_NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (gesture == GESTURE_NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        gesture = if (abs(dx) > abs(dy)) GESTURE_H else GESTURE_V
                    }
                    if (gesture == GESTURE_H) {
                        itemText.translationX = dx
                        itemText.alpha = 1f - minOf(1f, abs(dx) / maxOf(1, slot.width))
                    } else if (gesture == GESTURE_V) {
                        card.translationY = dy * 0.5f
                        card.alpha = 1f - minOf(0.5f, abs(dy) / dp(240))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    finishGesture(dx, dy, e.actionMasked == MotionEvent.ACTION_CANCEL)
                }
            }
            true
        }
    }

    private fun finishGesture(dx: Float, dy: Float, cancelled: Boolean) {
        val g = gesture
        gesture = GESTURE_NONE
        if (g == GESTURE_H) {
            if (!cancelled && abs(dx) > dp(60) && items.size > 1) {
                slideItem(dx < 0)
            } else {
                snapBackItem()
            }
        } else if (g == GESTURE_V) {
            if (!cancelled && dy < -dp(60)) {
                closeWithAnimation()
            } else if (!cancelled && dy > dp(60)) {
                minimize()
            } else {
                snapBackCard()
            }
        }
    }

    private fun snapBackItem() {
        itemText.animate().translationX(0f).alpha(1f).setDuration(120).start()
    }

    private fun snapBackCard() {
        card.animate().translationY(0f).alpha(1f).setDuration(120).start()
    }

    /** Swipe left (toLeft = true) shows the next item; swipe right shows the previous one. */
    private fun slideItem(toLeft: Boolean) {
        busy = true
        val w = maxOf(1, slot.width).toFloat()
        val outX = if (toLeft) -w else w
        itemText.animate().translationX(outX).alpha(0f).setDuration(140).withEndAction {
            val n = items.size
            index = if (toLeft) (index + 1) % n else (index - 1 + n) % n
            save()
            refresh()
            itemText.translationX = -outX
            itemText.animate().translationX(0f).alpha(1f).setDuration(160)
                .withEndAction { busy = false }
                .start()
        }.start()
    }

    private fun closeWithAnimation() {
        busy = true
        card.animate().translationY(-dp(60).toFloat()).alpha(0f).setDuration(180)
            .withEndAction { stopSelf() }
            .start()
    }

    private fun minimize() {
        if (busy) {
            snapBackCard()
            return
        }
        busy = true
        card.animate().translationY(dp(24).toFloat()).alpha(0f).setDuration(150).withEndAction {
            val (sw, _) = screenSize()
            // Keep the side of the card that is nearer a screen edge in place.
            if (params.x + cardW / 2 > sw / 2) params.x += cardW - bubbleW
            minimized = true
            card.visibility = View.GONE
            card.translationY = 0f
            card.alpha = 1f
            bubble.alpha = 0f
            bubble.visibility = View.VISIBLE
            clampToScreen()
            wm.updateViewLayout(rootView, params)
            bubble.animate().alpha(1f).setDuration(150).withEndAction { busy = false }.start()
        }.start()
    }

    private fun restore() {
        if (busy) return
        busy = true
        val (sw, _) = screenSize()
        if (params.x + bubbleW / 2 > sw / 2) params.x -= cardW - bubbleW
        minimized = false
        bubble.visibility = View.GONE
        card.alpha = 0f
        card.visibility = View.VISIBLE
        clampToScreen()
        wm.updateViewLayout(rootView, params)
        savePosition()
        card.animate().alpha(1f).setDuration(150).withEndAction { busy = false }.start()
    }

    // ---------- list actions ----------

    private fun refresh() {
        itemText.text = if (items.isEmpty()) "Nothing to do \u2013 tap +" else items[index]
    }

    private fun deleteCurrent() {
        if (busy || items.isEmpty()) return
        items.removeAt(index)
        if (index >= items.size) index = 0
        save()
        refresh()
    }

    private fun showAdd() {
        if (busy) return
        input.setText("")
        viewRow.visibility = View.GONE
        addRow.visibility = View.VISIBLE
        // If the card sits low on the screen the keyboard would cover it, so lift it while typing.
        val (_, sh) = screenSize()
        yBeforeAdd = params.y
        if (params.y + curH() > sh * 0.45f) params.y = dp(60)
        // The window must be focusable for the keyboard to type into it.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.updateViewLayout(rootView, params)
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun hideAdd() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        addRow.visibility = View.GONE
        viewRow.visibility = View.VISIBLE
        if (yBeforeAdd >= 0) {
            params.y = yBeforeAdd
            yBeforeAdd = -1
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(rootView, params)
    }

    private fun commitAdd() {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) {
            items.add(text)
            index = items.size - 1
            save()
            refresh()
        }
        hideAdd()
    }
}
