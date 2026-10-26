package com.insou.autoa11y.shell

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku UserService process as uid 2000 (shell) or 0 (root).
 * No Android Context here — only shell commands and plain Java IO.
 */
class ShellService : IShellService.Stub() {

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"

    private fun flagOpts(flags: Int): String = buildString {
        if (flags and 1 != 0) append(" -r")
        if (flags and 2 != 0) append(" -t")
        if (flags and 4 != 0) append(" -d")
        if (flags and 8 != 0) append(" -g")
        if (flags and 16 != 0) append(" --bypass-low-target-sdk-block")
    }

    override fun setA11yEnabled(component: String, enable: Boolean): String {
        val cur = sh("settings get secure enabled_accessibility_services")
            .removePrefix("null").trim()
        val list = cur.split(':').filter { it.isNotBlank() }.toMutableList()
        if (enable) {
            if (component !in list) list.add(component)
        } else {
            list.remove(component)
        }
        val value = list.joinToString(":")
        sh("settings put secure enabled_accessibility_services ${quote(value)}")
        sh("settings put secure accessibility_enabled ${if (list.isEmpty()) 0 else 1}")
        return value
    }

    override fun setNotificationListener(component: String, enable: Boolean): String {
        val sub = if (enable) "allow_listener" else "disallow_listener"
        sh("cmd notification $sub ${quote(component)}")
        return sh("settings get secure enabled_notification_listeners").removePrefix("null").trim()
    }

    override fun installApk(apk: ParcelFileDescriptor?, flags: Int): String {
        if (apk == null) return "Error: null fd"
        val tmp = tmpFile("apk")
        try {
            copyFd(apk, tmp)
            return sh("pm install${flagOpts(flags)} ${quote(tmp.absolutePath)}")
        } finally {
            tmp.delete()
        }
    }

    override fun installSplits(bundleZip: ParcelFileDescriptor?, pkgName: String, flags: Int): String {
        if (bundleZip == null) return "Error: null fd"
        val dir = File("/data/local/tmp/a11ykit_splits_${System.currentTimeMillis()}")
        val zip = File(dir.parentFile, dir.name + ".zip")
        try {
            copyFd(bundleZip, zip)
            if (!dir.mkdirs()) return "Error: cannot mkdir ${dir.absolutePath}"

            val zf = ZipFile(zip)
            try {
                val entries = zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk") }
                    .toList()
                if (entries.isEmpty()) return "Error: no apk entries in bundle"

                for (e in entries) {
                    val out = File(dir, e.name.substringAfterLast('/'))
                    zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }

                val create = sh("pm install-create${flagOpts(flags)}")
                val sessionId = Regex("\\d+").find(create)?.value
                    ?: return "Error: no session id from:\n$create"

                for (e in entries) {
                    val name = e.name.substringAfterLast('/')
                    val out = File(dir, name)
                    val write = sh("pm install-write -S ${out.length()} $sessionId ${quote(name)} ${quote(out.absolutePath)}")
                    if (write.isNotBlank() && !write.contains("Success", ignoreCase = true)) {
                        sh("pm install-abandon $sessionId")
                        return "Error writing $name:\n$write"
                    }
                }
                val commit = sh("pm install-commit $sessionId").ifBlank { "Success (empty output)" }

                // xapk: copy *.obb into /sdcard/Android/obb/<pkg>/
                val obbEntries = zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".obb") }
                    .toList()
                if (obbEntries.isNotEmpty() && pkgName.isNotBlank()) {
                    val obbDir = File("/sdcard/Android/obb/${pkgName.trim()}")
                    obbDir.mkdirs()
                    for (e in obbEntries) {
                        val out = File(obbDir, e.name.substringAfterLast('/'))
                        zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                    return "$commit\nOBB: ${obbEntries.size} 个已复制到 ${obbDir.absolutePath}"
                }
                return commit
            } finally {
                zf.close()
            }
        } finally {
            zip.delete()
            dir.deleteRecursively()
        }
    }

    override fun getAppOps(pkg: String, op: String): String {
        return sh("appops get ${quote(pkg)} ${quote(op)}")
    }

    override fun queryAppOps(op: String, mode: String): String {
        return sh("appops query-op ${quote(op)} ${quote(mode)}")
    }

    override fun queryInstallRequesters(): String {
        val cmd = "dumpsys package packages | awk '/^  Package \\[/{pkg=\$2} /android\\.permission\\.REQUEST_INSTALL_PACKAGES/{print pkg}' | sort -u | tr -d '[]'"
        return sh(cmd)
    }

    override fun setAppOps(pkg: String, op: String, mode: String): String {
        sh("appops set ${quote(pkg)} ${quote(op)} ${quote(mode)}")
        return sh("appops get ${quote(pkg)} ${quote(op)}")
    }

    private fun tmpFile(ext: String) =
        File("/data/local/tmp/a11ykit_install_${System.currentTimeMillis()}.$ext")

    private fun copyFd(pfd: ParcelFileDescriptor, target: File) {
        FileInputStream(pfd.fileDescriptor).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        pfd.close()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
