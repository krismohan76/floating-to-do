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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.floatingtodo.STOP"
        private const val CHANNEL_ID = "floating_todo"
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var itemText: TextView
    private lateinit var viewRow: LinearLayout
    private lateinit var addRow: LinearLayout
    private lateinit var input: EditText
    private var root: LinearLayout? = null

    private val items = mutableListOf<String>()
    private var index = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (root == null) {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences("todo", MODE_PRIVATE)
            loadItems()
            try {
                buildOverlay()
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::wm.isInitialized) {
            root?.let {
                try {
                    wm.removeView(it)
                } catch (e: Exception) {
                }
            }
        }
        root = null
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

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 34, 34, 34))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }

        // Row 1: current item + minus + plus. Swipe on the text shows the next item.
        itemText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        var downX = 0f
        var downY = 0f
        itemText.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(40) && abs(dx) > abs(dy)) next()
                }
            }
            true
        }
        viewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(itemText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("\u2212", 24f) { deleteCurrent() })
            addView(button("+", 24f) { showAdd() })
        }

        // Row 2 (hidden until + is tapped): text box + confirm + cancel.
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
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("\u2713", 20f) { commitAdd() })
            addView(button("\u2715", 18f) { hideAdd() })
        }

        card.addView(viewRow)
        card.addView(addRow)

        params = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(80)
        }

        root = card
        refresh()
        wm.addView(card, params)
    }

    private fun refresh() {
        itemText.text = if (items.isEmpty()) "Nothing to do \u2013 tap +" else items[index]
    }

    private fun next() {
        if (items.isEmpty()) return
        index = (index + 1) % items.size
        save()
        refresh()
    }

    private fun deleteCurrent() {
        if (items.isEmpty()) return
        items.removeAt(index)
        if (index >= items.size) index = 0
        save()
        refresh()
    }

    private fun showAdd() {
        input.setText("")
        viewRow.visibility = View.GONE
        addRow.visibility = View.VISIBLE
        // The window must be focusable for the keyboard to type into it.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.updateViewLayout(root, params)
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
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, params)
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
