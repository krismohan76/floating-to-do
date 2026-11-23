package com.example.floatingtodo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val startBtn = Button(this).apply {
            text = "Show floating list"
            setOnClickListener { startOverlay() }
        }
        val stopBtn = Button(this).apply {
            text = "Close floating list"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_STOP)
                )
            }
        }
        layout.addView(status)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) {
            "Permission granted. Tap \"Show floating list\"."
        } else {
            "Tap \"Show floating list\", then allow \"Display over other apps\" and come back."
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
