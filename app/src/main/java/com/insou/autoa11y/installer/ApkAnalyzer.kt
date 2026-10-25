package com.insou.autoa11y.installer

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

enum class ApkKind { SINGLE_APK, SPLIT_BUNDLE, UNKNOWN }

data class PkgInfo(
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val splits: List<String>,
)

object ApkAnalyzer {

    /** Content-based detection: never trust file extensions (.apk.1 etc). */
    fun detect(file: File): ApkKind {
        if (!file.exists() || file.length() < 4) return ApkKind.UNKNOWN
        return try {
            ZipFile(file).use { zf ->
                val apkEntries = zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk") }
                    .map { it.name }
                    .toList()
                val hasManifest = zf.getEntry("AndroidManifest.xml") != null
                when {
                    apkEntries.isNotEmpty() -> ApkKind.SPLIT_BUNDLE
                    hasManifest -> ApkKind.SINGLE_APK
                    else -> ApkKind.UNKNOWN
                }
            }
        } catch (_: Exception) {
            ApkKind.UNKNOWN
        }
    }

    /**
     * Parse package info. For bundles, extracts base.apk (or the first apk entry)
     * next to [file] for parsing.
     */
    fun parse(context: Context, file: File, kind: ApkKind): PkgInfo? {
        return when (kind) {
            ApkKind.SINGLE_APK -> parseApk(context, file, emptyList())
            ApkKind.SPLIT_BUNDLE -> {
                ZipFile(file).use { zf ->
                    val names = zf.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk") }
                        .map { it.name.substringAfterLast('/') }
                        .toList()
                    val baseName = names.firstOrNull { it == "base.apk" } ?: names.firstOrNull()
                        ?: return null
                    val baseFile = File(file.parentFile, "parse_base.apk")
                    zf.getInputStream(zf.getEntry(names.first { it.substringAfterLast('/') == baseName }))
                        .use { input -> baseFile.outputStream().use { input.copyTo(it) } }
                    val info = parseApk(context, baseFile, names.sorted())
                    baseFile.delete()
                    info
                }
            }

            ApkKind.UNKNOWN -> null
        }
    }

    private fun parseApk(context: Context, apk: File, splits: List<String>): PkgInfo? {
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
        )
    }
}
