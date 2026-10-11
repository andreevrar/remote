package app.tvremote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Запасной путь и ввод текста.
 * Ввод текста (в том числе кириллицы) идёт через ACTION_SET_TEXT — через ADB `input text` русские буквы не умеет.
 * Если ADB недоступен, служба ещё умеет Назад/Домой, двигать фокус и нажимать на выбранный элемент.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------- глобальные действия ----------

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // ---------- фокус и клик ----------

    private fun focused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun firstFocusable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser && n.childCount == 0) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = firstFocusable(child)
            if (found != null) return found
        }
        return null
    }

    /** direction: View.FOCUS_UP / DOWN / LEFT / RIGHT */
    fun move(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val cur = focused(root)
        val target = if (cur != null) cur.focusSearch(direction) else firstFocusable(root)
        if (target != null) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
            if (target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) return true
        }
        // фокус двигать некуда — пробуем прокрутить список
        val forward = direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT
        var n: AccessibilityNodeInfo? = cur ?: root
        while (n != null) {
            if (n.isScrollable) {
                return n.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            }
            n = n.parent
        }
        return false
    }

    fun click(): Boolean {
        val root = rootInActiveWindow ?: return false
        var n = focused(root)
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    // ---------- ввод текста ----------

    private fun firstEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = firstEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findInput(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { w -> w.root?.let { r -> roots.add(r) } }
        } catch (_: Throwable) {
        }
        for (r in roots) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        for (r in roots) {
            val e = firstEditable(r)
            if (e != null) return e
        }
        return null
    }

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setNodeText(n: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Дописать (append=true) или заменить текст в активном поле ввода. */
    fun setText(text: String, append: Boolean): Boolean {
        val node = findInput() ?: return false
        val value = if (append) currentText(node) + text else text
        return setNodeText(node, value)
    }

    /** Удалить последний символ в активном поле ввода. */
    fun deleteLast(): Boolean {
        val node = findInput() ?: return false
        val cur = currentText(node)
        if (cur.isEmpty()) return true
        return setNodeText(node, cur.dropLast(1))
    }
}
