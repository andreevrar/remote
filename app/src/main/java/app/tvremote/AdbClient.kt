package app.tvremote

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Подключение к ADB самого телевизора (127.0.0.1:5555).
 * При первом подключении на экране ТВ появится запрос «Разрешить отладку по USB/ADB?» —
 * нужно нажать «Всегда разрешать».
 */
class AdbClient(private val ctx: Context) {
    private var dadb: Dadb? = null

    @Synchronized
    private fun connect(): Dadb? {
        dadb?.let { return it }
        return try {
            val priv = File(ctx.filesDir, "adbkey")
            val pub = File(ctx.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) {
                AdbKeyPair.generate(priv, pub)
            }
            val keyPair = AdbKeyPair.read(priv, pub)
            Dadb.create("127.0.0.1", 5555, keyPair).also { dadb = it }
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    private fun drop() {
        try {
            dadb?.close()
        } catch (_: Throwable) {
        }
        dadb = null
    }

    /** Выполнить команду. true — если выполнена без ошибок. */
    @Synchronized
    fun shell(cmd: String): Boolean {
        val d = connect() ?: return false
        return try {
            d.shell(cmd).exitCode == 0
        } catch (e: Throwable) {
            drop()
            false
        }
    }

    /** Выполнить команду и вернуть вывод (null — при ошибке). */
    @Synchronized
    fun shellOut(cmd: String): String? {
        val d = connect() ?: return null
        return try {
            d.shell(cmd).output
        } catch (e: Throwable) {
            drop()
            null
        }
    }

    /** Включает службу специальных возможностей приложения через ADB, не трогая остальные службы. */
    fun enableAccessibility(): Boolean {
        val cur = (shellOut("settings get secure enabled_accessibility_services") ?: return false).trim()
        if (!cur.contains(Config.ACC_ID)) {
            val list = if (cur.isEmpty() || cur == "null") Config.ACC_ID else "$cur:${Config.ACC_ID}"
            if (!shell("settings put secure enabled_accessibility_services $list")) return false
        }
        return shell("settings put secure accessibility_enabled 1")
    }
}
