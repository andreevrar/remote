package app.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var codeView: TextView
    private lateinit var infoView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(40), dp(28), dp(40), dp(28))
        root.setBackgroundColor(0xFF101418.toInt())

        root.addView(label("ТВ-пульт", 30f, 0xFFFFFFFF.toInt()))
        root.addView(label("Код для страницы пульта:", 18f, 0xFF9AA4AF.toInt()))
        codeView = label("", 44f, 0xFF4DA3FF.toInt())
        root.addView(codeView)
        infoView = label("", 18f, 0xFFDDE3EA.toInt())
        root.addView(infoView)

        val first = button("Перезапустить службу") { restartService(false) }
        root.addView(first)
        root.addView(button("Включить спец. возможности через ADB") { enableAccessibility() })
        root.addView(button("Открыть настройки спец. возможностей") { openAccessibilitySettings() })
        root.addView(button("Сгенерировать новый код") { restartService(true) })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        first.requestFocus()

        startControl()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = sizeSp
        tv.setTextColor(color)
        val pad = (6 * resources.displayMetrics.density).toInt()
        tv.setPadding(0, pad, 0, pad)
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun refresh() {
        codeView.text = Config.pretty(Config.code(this))
        val acc = if (RemoteAccessibilityService.instance != null) "включены" else "выключены"
        val adb = if (Status.adbOk) "подключено"
        else "нет. Включите отладку по ADB и нажмите «Всегда разрешать» в запросе на экране"
        infoView.text = "Брокер: " + Config.broker(this) + "\n" +
            "Связь: " + Status.mqtt + "\n" +
            "ADB: " + adb + "\n" +
            "Спец. возможности: " + acc + "\n" +
            "Последняя команда: " + Status.lastCmd
    }

    private fun startControl() {
        val svc = Intent(this, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun restartService(newCode: Boolean) {
        stopService(Intent(this, ControlService::class.java))
        if (newCode) Config.regenerate(this)
        Status.mqtt = "перезапуск…"
        startControl()
        refresh()
    }

    private fun enableAccessibility() {
        val appCtx = applicationContext
        Thread {
            val ok = try {
                AdbClient(appCtx).enableAccessibility()
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Спец. возможности включены" else "Не вышло: нет доступа к ADB",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
            Toast.makeText(this, "На этом ТВ нет такого экрана — используйте кнопку выше", Toast.LENGTH_LONG).show()
        }
    }
}
