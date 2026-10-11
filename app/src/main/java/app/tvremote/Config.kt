package app.tvremote

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Config {
    const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
    const val ACC_ID = "app.tvremote/app.tvremote.RemoteAccessibilityService"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Секретный код ТВ: он же часть имени MQTT-топика. */
    fun code(c: Context): String {
        val saved = prefs(c).getString("code", null)
        return saved ?: regenerate(c)
    }

    fun regenerate(c: Context): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder()
        repeat(12) { sb.append(chars[rnd.nextInt(chars.length)]) }
        val v = sb.toString()
        prefs(c).edit().putString("code", v).apply()
        return v
    }

    fun pretty(code: String): String = code.chunked(4).joinToString("-")

    /** Пары: адрес для ТВ (ssl) и адрес для страницы пульта (wss). Сервер у пары один и тот же. */
    val BROKERS: List<Pair<String, String>> = listOf(
        Pair("ssl://broker.hivemq.com:8883", "wss://broker.hivemq.com:8884/mqtt"),
        Pair("ssl://broker.emqx.io:8883", "wss://broker.emqx.io:8084/mqtt")
    )

    private fun brokerIndex(c: Context): Int =
        prefs(c).getInt("broker_idx", 0).coerceIn(0, BROKERS.size - 1)

    fun broker(c: Context): String = BROKERS[brokerIndex(c)].first

    fun pageBroker(c: Context): String = BROKERS[brokerIndex(c)].second

    fun nextBroker(c: Context) {
        prefs(c).edit().putInt("broker_idx", (brokerIndex(c) + 1) % BROKERS.size).apply()
    }
}

/** Общее состояние для экрана приложения. */
object Status {
    @Volatile var mqtt: String = "не подключено"
    @Volatile var adbOk: Boolean = false
    @Volatile var lastCmd: String = "—"
}

/** Разрешённые кнопки и их коды Android KeyEvent. Ничего кроме этого список ТВ не выполняет. */
object Keys {
    val codes: Map<String, Int> = mapOf(
        "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23,
        "ENTER" to 66, "BACK" to 4, "HOME" to 3, "MENU" to 82,
        "VOL_UP" to 24, "VOL_DOWN" to 25, "MUTE" to 164,
        "CH_UP" to 166, "CH_DOWN" to 167,
        "POWER" to 26, "SLEEP" to 223, "WAKEUP" to 224,
        "PLAY_PAUSE" to 85, "REWIND" to 89, "FF" to 90, "NEXT" to 87, "PREV" to 88,
        "DEL" to 67, "SPACE" to 62, "SEARCH" to 84, "SETTINGS" to 176, "INFO" to 165
    ) + (0..9).associate { it.toString() to (7 + it) }
}
