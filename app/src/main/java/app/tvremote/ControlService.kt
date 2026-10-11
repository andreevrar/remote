package app.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Фоновая служба: держит соединение с MQTT-брокером, слушает команды страницы-пульта
 * и выполняет их (ADB `input keyevent` или службу специальных возможностей).
 *
 * Топики:  tvr/<код>/cmd     — команды от страницы
 *          tvr/<код>/status  — состояние ТВ (retained JSON)
 */
class ControlService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    // отдельный поток для MQTT: ожидание ADB-разрешения не должно блокировать переподключение
    private val mqttSched = Executors.newSingleThreadScheduledExecutor()
    private val pending = AtomicInteger(0)

    private lateinit var adb: AdbClient
    private var client: MqttAsyncClient? = null
    private var base = ""
    private var started = false
    @Volatile private var stopped = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!started) {
            started = true
            init()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        try {
            val c = client
            if (c != null && c.isConnected) {
                val m = MqttMessage("{\"online\":false}".toByteArray())
                m.qos = 1
                m.isRetained = true
                c.publish("$base/status", m)
                c.disconnect()
            }
        } catch (_: Throwable) {
        }
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        sched.shutdownNow()
        mqttSched.shutdownNow()
        worker.shutdownNow()
        try {
            wake?.release()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---------- запуск ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "remote"
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ТВ-пульт", NotificationManager.IMPORTANCE_LOW)
            )
            builder = Notification.Builder(this, channelId)
        } else {
            builder = Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("ТВ-пульт работает")
            .setContentText("Код: " + Config.pretty(Config.code(this)))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, n)
    }

    private fun init() {
        adb = AdbClient(applicationContext)
        base = "tvr/" + Config.code(this)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:service")
            wake?.acquire()
        } catch (_: Throwable) {
        }
        sched.execute { probeAdb() }
        connectMqtt()
    }

    // ---------- ADB ----------

    private var accEnsured = false

    private fun probeAdb() {
        if (stopped) return
        val ok = adb.shell("echo ok")
        Status.adbOk = ok
        if (ok && !accEnsured) {
            accEnsured = true
            adb.enableAccessibility()
        }
        publishStatus()
        if (!stopped) {
            sched.schedule({ probeAdb() }, if (ok) 60L else 30L, TimeUnit.SECONDS)
        }
    }

    // ---------- MQTT ----------

    private fun connectMqtt() {
        if (stopped) return
        try {
            val cid = "tv-" + Config.code(this).take(6) + "-" + (System.currentTimeMillis() % 100000)
            val c = MqttAsyncClient(Config.broker(this), cid, MemoryPersistence())
            client = c
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Status.mqtt = "подключено"
                    try {
                        c.subscribe("$base/cmd", 0)
                    } catch (_: Throwable) {
                    }
                    publishStatus()
                }

                override fun connectionLost(cause: Throwable?) {
                    Status.mqtt = "связь потеряна, переподключаюсь…"
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val body = message?.payload ?: return
                    handle(String(body, Charsets.UTF_8))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val o = MqttConnectOptions()
            o.isAutomaticReconnect = true
            o.isCleanSession = true
            o.keepAliveInterval = 30
            o.connectionTimeout = 15
            o.setWill("$base/status", "{\"online\":false}".toByteArray(), 1, true)

            Status.mqtt = "подключаюсь…"
            c.connect(o, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Status.mqtt = "ошибка: " + (exception?.message ?: "нет связи")
                    try {
                        c.close()
                    } catch (_: Throwable) {
                    }
                    if (!stopped) mqttSched.schedule({ connectMqtt() }, 10L, TimeUnit.SECONDS)
                }
            })

            // если за 25 секунд соединение не установилось (бывает, что оно просто зависает), пробуем заново
            mqttSched.schedule({
                if (!stopped && client === c && !c.isConnected) {
                    Status.mqtt = "нет ответа от брокера, пробую снова…"
                    try {
                        c.disconnectForcibly(0, 0)
                    } catch (_: Throwable) {
                    }
                    try {
                        c.close()
                    } catch (_: Throwable) {
                    }
                    connectMqtt()
                }
            }, 25L, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            Status.mqtt = "ошибка: " + (e.message ?: e.javaClass.simpleName)
            if (!stopped) mqttSched.schedule({ connectMqtt() }, 10L, TimeUnit.SECONDS)
        }
    }

    private fun publishStatus() {
        val c = client ?: return
        try {
            if (!c.isConnected) return
            val j = JSONObject()
                .put("online", true)
                .put("adb", Status.adbOk)
                .put("acc", RemoteAccessibilityService.instance != null)
            val m = MqttMessage(j.toString().toByteArray())
            m.qos = 1
            m.isRetained = true
            c.publish("$base/status", m)
        } catch (_: Throwable) {
        }
    }

    // ---------- команды ----------

    private fun handle(payload: String) {
        val j = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return
        }
        val t = j.optString("t")
        // если очередь забита (зажата стрелка), лишние нажатия навигации пропускаем,
        // а текст и удаление символов никогда не теряем
        if (pending.get() > 6 && t == "key" && j.optString("k") != "DEL") return
        pending.incrementAndGet()
        try {
            worker.execute {
                try {
                    runCommand(j)
                } catch (_: Throwable) {
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: Throwable) {
            pending.decrementAndGet()
        }
    }

    private fun runCommand(j: JSONObject) {
        when (j.optString("t")) {
            "key" -> key(j.optString("k"))
            "text" -> text(j.optString("v"), j.optString("m") != "replace")
            "ping" -> publishStatus()
        }
    }

    private fun key(name: String) {
        val code = Keys.codes[name] ?: return
        Status.lastCmd = name
        if (Status.adbOk) {
            if (adb.shell("input keyevent $code")) return
            Status.adbOk = false
            publishStatus()
        }
        // режим без ADB: громкость и медиа-кнопки обрабатываются самой системой
        if (softKey(name, code)) return
        // остальное через службу специальных возможностей
        val acc = RemoteAccessibilityService.instance ?: return
        when (name) {
            "POWER" -> acc.powerDialog()
            "SETTINGS" -> acc.openSettings()
            "BACK" -> acc.back()
            "HOME" -> acc.home()
            "UP" -> acc.move(View.FOCUS_UP)
            "DOWN" -> acc.move(View.FOCUS_DOWN)
            "LEFT" -> acc.move(View.FOCUS_LEFT)
            "RIGHT" -> acc.move(View.FOCUS_RIGHT)
            "OK", "ENTER" -> acc.click()
            "DEL" -> acc.deleteLast()
        }
    }

    /** Кнопки, которые работают без ADB и без спец. возможностей. true — кнопка обработана. */
    private fun softKey(name: String, code: Int): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            when (name) {
                "VOL_UP" -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
                "VOL_DOWN" -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                )
                "MUTE" -> if (Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI
                    )
                } else {
                    return false
                }
                "PLAY_PAUSE", "NEXT", "PREV", "REWIND", "FF" -> {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                else -> return false
            }
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    private fun text(v: String, append: Boolean) {
        Status.lastCmd = "текст"
        val acc = RemoteAccessibilityService.instance
        if (acc != null && acc.setText(v, append)) return
        // без службы специальных возможностей — только латиница через ADB, и только дописывание
        if (append && Status.adbOk) adbText(v)
    }

    private fun adbText(v: String) {
        val sb = StringBuilder()
        for (ch in v) {
            when {
                ch == ' ' -> sb.append("%s")
                ch.code < 128 && ch.isLetterOrDigit() -> sb.append(ch)
                ".,-_@:/+=".indexOf(ch) >= 0 -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) adb.shell("input text '$sb'")
    }
}
