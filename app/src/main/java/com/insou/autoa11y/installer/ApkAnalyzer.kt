package com.insou.autoa11y.installer

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

enum class ApkKind { SINGLE_APK, SPLIT_BUNDLE, ENCRYPTED, UNKNOWN }

data class PkgInfo(
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val splits: List<String>,
    val obbCount: Int,
)

object ApkAnalyzer {

    /** Content-based detection: never trust file extensions (.apk.1 etc). */
    fun detect(file: File): ApkKind {
        if (!file.exists() || file.length() < 4) return ApkKind.UNKNOWN
        return try {
            ZipFile(file).use { zf ->
                val names = zf.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name.substringAfterLast('/') }
                    .toList()
                when {
                    // APKMirror marker present in (encrypted) .apkm bundles
                    names.any { it == "stamp-cert-signed" } -> ApkKind.ENCRYPTED
                    names.any { it.endsWith(".apk") } -> ApkKind.SPLIT_BUNDLE
                    names.any { it == "AndroidManifest.xml" } -> ApkKind.SINGLE_APK
                    else -> ApkKind.UNKNOWN
                }
            }
        } catch (_: Exception) {
            ApkKind.UNKNOWN
        }
    }

    private fun isConfigSplit(name: String): Boolean =
        name.startsWith("split_") || name.startsWith("config.")

    /**
     * Parse package info from the bundle's main apk. For .xapk the main apk is
     * named <pkg>.apk rather than base.apk; pick the first non-config entry.
     */
    fun parse(context: Context, file: File, kind: ApkKind): PkgInfo? {
        return when (kind) {
            ApkKind.SINGLE_APK -> parseApk(context, file, emptyList(), 0)
            ApkKind.SPLIT_BUNDLE -> {
                ZipFile(file).use { zf ->
                    val names = zf.entries().asSequence()
                        .filter { !it.isDirectory }
                        .map { it.name.substringAfterLast('/') }
                        .toList()
                    val apks = names.filter { it.endsWith(".apk") }.sorted()
                    val obbCount = names.count { it.endsWith(".obb") }
                    val baseName = apks.firstOrNull { it == "base.apk" }
                        ?: apks.firstOrNull { !isConfigSplit(it) }
                        ?: apks.firstOrNull()
                        ?: return null
                    val baseFile = File(file.parentFile, "parse_base.apk")
                    zf.getInputStream(zf.getEntry(names.first { it.substringAfterLast('/') == baseName }))
                        .use { input -> baseFile.outputStream().use { input.copyTo(it) } }
                    val info = parseApk(context, baseFile, apks, obbCount)
                    baseFile.delete()
                    info
                }
            }

            else -> null
        }
    }

    private fun parseApk(context: Context, apk: File, splits: List<String>, obbCount: Int): PkgInfo? {
        val pm = context.packageManager
        val pkg = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val appName = try {
            pkg.applicationInfo?.sourceDir = apk.absolutePath
            pkg.applicationInfo?.loadLabel(pm)?.toString()
        } catch (_: Exception) {
            null
        } ?: pkg.packageName
        return PkgInfo(
            appName = appName,
            packageName = pkg.packageName,
            versionName = pkg.versionName,
            versionCode = pkg.longVersionCode,
            splits = splits,
            obbCount = obbCount,
        )
    }
}
