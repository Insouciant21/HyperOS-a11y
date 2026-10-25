package com.insou.autoa11y.ui

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insou.autoa11y.installer.ApkAnalyzer
import com.insou.autoa11y.installer.ApkKind
import com.insou.autoa11y.installer.PkgInfo
import com.insou.autoa11y.shell.ShellGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Staged(
    val cacheFile: File,
    val displayName: String,
    val sizeBytes: Long,
    val kind: ApkKind,
    val info: PkgInfo?,
)

@Composable
fun InstallScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var staged by remember { mutableStateOf<Staged?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    var optReplace by remember { mutableStateOf(true) }
    var optTest by remember { mutableStateOf(true) }
    var optDowngrade by remember { mutableStateOf(false) }
    var optGrant by remember { mutableStateOf(false) }
    var optBypass by remember { mutableStateOf(false) }

    fun flags(): Int = (if (optReplace) 1 else 0) or (if (optTest) 2 else 0) or
        (if (optDowngrade) 4 else 0) or (if (optGrant) 8 else 0) or (if (optBypass) 16 else 0)

    fun stage(uri: Uri) {
        analyzing = true
        result = null
        staged = null
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { analyze(context, uri) }
                staged = s
            } catch (e: Throwable) {
                result = "分析失败: ${e.message ?: e.toString()}"
            } finally {
                analyzing = false
            }
        }
    }

    fun install() {
        val s = staged ?: return
        installing = true
        result = null
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    val pfd = ParcelFileDescriptor.open(s.cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val out = if (s.kind == ApkKind.SPLIT_BUNDLE) {
                        ShellGateway.call { it.installSplits(pfd, flags()) }
                    } else {
                        ShellGateway.call { it.installApk(pfd, flags()) }
                    }
                    out.ifBlank { "Success" }
                }
                result = output
            } catch (e: Throwable) {
                result = "失败: ${e.message ?: e.toString()}"
            } finally {
                installing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stage(uri)
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(result) {
        if (result != null) {
            kotlinx.coroutines.delay(250)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        SmallTitle(text = "文件")

        Card(modifier = Modifier.padding(vertical = 4.dp)) {
            BasicComponent(
                onClick = { if (!analyzing && !installing) picker.launch(arrayOf("*/*")) },
                enabled = !analyzing && !installing,
                content = {
                    Text(
                        text = when {
                            analyzing -> "正在分析…"
                            staged != null -> "重新选择文件"
                            else -> "选择 .apk / .apks / .apkm / .apk.1"
                        },
                    )
                    Text(
                        text = "内容自动识别格式，经 Shizuku 以 shell 权限静默安装",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
        }

        staged?.let { s ->
            val kindLabel = when {
                s.kind == ApkKind.SINGLE_APK -> "单 APK"
                s.kind == ApkKind.SPLIT_BUNDLE -> "拆分包（${s.info?.splits?.size ?: "?"} 个 apk）"
                else -> "未知格式"
            }
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                BasicComponent(
                    content = {
                        Text(text = s.displayName)
                        Text(
                            text = "$kindLabel · ${s.sizeBytes / 1024 / 1024} MB",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
            }

            s.info?.let { info ->
                SmallTitle(text = "应用信息")
                Card(modifier = Modifier.padding(vertical = 4.dp)) {
                    BasicComponent(
                        content = {
                            Text(text = "${info.appName}  ${info.versionName ?: ""} (${info.versionCode})")
                            Text(
                                text = info.packageName +
                                    if (info.splits.isNotEmpty()) " · ${info.splits.joinToString(", ") { it.removePrefix("split_") }}" else "",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        },
                    )
                }
            }

            SmallTitle(text = "安装选项")
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                OptionRow("替换已安装应用 (-r)", optReplace) { optReplace = it }
                OptionRow("允许测试包 (-t)", optTest) { optTest = it }
                OptionRow("允许降级 (-d)", optDowngrade) { optDowngrade = it }
                OptionRow("授予全部运行时权限 (-g)", optGrant) { optGrant = it }
                OptionRow("绕过低 targetSdk 拦截", optBypass) { optBypass = it }
            }

            Button(
                onClick = { install() },
                enabled = !installing && s.kind != ApkKind.UNKNOWN,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Text(text = if (installing) "安装中…" else "开始静默安装")
            }
        }

        result?.let {
            SmallTitle(text = "结果")
            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                BasicComponent(content = { Text(text = it) })
            }
        }
    }
}

private fun analyze(context: Context, uri: Uri): Staged {
    val cacheDir = File(context.cacheDir, "install").apply { mkdirs() }
    val cacheFile = File(cacheDir, "staged_${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        cacheFile.outputStream().use { input.copyTo(it) }
    }
    val kind = ApkAnalyzer.detect(cacheFile)
    val info = if (kind == ApkKind.UNKNOWN) null else ApkAnalyzer.parse(context, cacheFile, kind)
    val display = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    return Staged(cacheFile, display, cacheFile.length(), kind, info)
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    BasicComponent(
        content = { Text(text = label) },
        endActions = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
