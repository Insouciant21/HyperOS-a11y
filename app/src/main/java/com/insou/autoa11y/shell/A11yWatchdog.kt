package com.insou.autoa11y.shell

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches enabled_accessibility_services; when a service the user enabled via
 * this app disappears (e.g. HyperOS auto-revokes it), re-enables it through
 * Shizuku. Backoff: at most 3 recoveries per component per 10 minutes; after
 * 3 consecutive failures the component is dropped from the watch list.
 */
object A11yWatchdog {

    private const val PREFS = "a11y_watchdog"
    private const val KEY_MASTER = "master"
    private const val KEY_WATCH = "watch"
    private const val KEY_FAILS = "fails"
    private const val DEBOUNCE_MS = 1500L
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_RETRIES_IN_WINDOW = 3
    private const val MAX_CONSECUTIVE_FAILS = 3

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val retryTimestamps = HashMap<String, MutableList<Long>>()
    private val checkMutex = Mutex()
    private val _watch = MutableStateFlow<Set<String>>(emptySet())
    val watch: StateFlow<Set<String>> = _watch

    private val observer = object : ContentObserver(handler) {
        private var pending: Runnable? = null
        override fun onChange(selfChange: Boolean) {
            pending?.let { handler.removeCallbacks(it) }
            pending = Runnable { scope.launch { check() } }.also { handler.postDelayed(it, DEBOUNCE_MS) }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_WATCH, emptySet())?.toSet() ?: emptySet()
        // A past session is not permission to revive a service that is off now.
        // Only services still enabled at startup can enter this session's watch.
        _watch.value = saved.map(::norm).intersect(currentEnabled())
        if (_watch.value != saved) {
            persistWatch(_watch.value)
            if (saved.map(::norm).toSet().size > _watch.value.size) {
                appendLog("已清理已关闭的历史保护项")
            }
        }
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
    }

    var master: Boolean
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MASTER, true)
        set(value) {
            if (value) {
                // Turning protection back on also starts from the current state.
                _watch.value = _watch.value.intersect(currentEnabled())
                persistWatch(_watch.value)
            }
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MASTER, value).apply()
        }

    fun onEnabledByApp(component: String) {
        if (!master) return
        val next = _watch.value + norm(component)
        _watch.value = next
        persistWatch(next)
        clearFails(component)
        appendLog("开始保护 ${shortName(component)}")
    }

    fun onDisabledByApp(component: String) {
        val next = _watch.value - norm(component)
        _watch.value = next
        persistWatch(next)
        clearFails(component)
        appendLog("停止保护 ${shortName(component)}")
    }

    /** A user's disable runs after any recovery already in flight, so it wins. */
    suspend fun setEnabledByUser(component: String, enable: Boolean): String {
        if (!enable) onDisabledByApp(component)
        return checkMutex.withLock {
            val result = ShellGateway.call { it.setA11yEnabled(component, enable) }
            if (enable) onEnabledByApp(component)
            result
        }
    }

    private fun persistWatch(set: Set<String>) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_WATCH, set).apply()
    }

    private fun currentEnabled(): Set<String> =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.map { norm(it) }
            ?.toSet()
            ?: emptySet()

    private fun norm(c: String): String =
        ComponentName.unflattenFromString(c)?.flattenToString() ?: c

    private suspend fun check() = checkMutex.withLock {
        if (!master) return@withLock
        val watching = _watch.value
        if (watching.isEmpty()) return@withLock
        val current = currentEnabled()
        val missing = watching.filter { norm(it) !in current }
        if (missing.isEmpty()) return@withLock

        for (component in missing) {
            if (!master || component !in _watch.value) continue
            val now = System.currentTimeMillis()
            val stamps = retryTimestamps.getOrPut(component) { mutableListOf() }
            stamps.removeAll { now - it > WINDOW_MS }
            if (stamps.size >= MAX_RETRIES_IN_WINDOW) continue
            if (fails(component) >= MAX_CONSECUTIVE_FAILS) {
                val next = _watch.value - component
                _watch.value = next
                persistWatch(next)
                appendLog("${shortName(component)} 连续失败，已移出保护")
                continue
            }
            stamps.add(now)
            appendLog("检测到 ${shortName(component)} 被关闭，正在恢复…")
            try {
                val restored = ShellGateway.call {
                    // Binding Shizuku can suspend. A user's disable or a master
                    // switch change during that wait must cancel the old work.
                    if (master && component in _watch.value && norm(component) !in currentEnabled()) {
                        it.setA11yEnabled(component, true)
                        true
                    } else false
                }
                if (restored) {
                    clearFails(component)
                    appendLog("已恢复 ${shortName(component)}")
                }
            } catch (e: Throwable) {
                incFails(component)
                appendLog("恢复失败 (${fails(component)}/${MAX_CONSECUTIVE_FAILS}): ${e.message ?: e.toString()}")
            }
        }
    }

    private fun fails(component: String): Int =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("$KEY_FAILS$component", 0)

    private fun incFails(component: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("$KEY_FAILS$component", fails(component) + 1).apply()
    }

    private fun clearFails(component: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("$KEY_FAILS$component").apply()
    }

    private fun appendLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        _log.value = (_log.value + line).takeLast(50)
    }

    private fun shortName(component: String): String =
        component.substringAfter('/').substringAfterLast('.')
}
