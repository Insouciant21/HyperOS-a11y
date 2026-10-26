package com.insou.autoa11y.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityManager

data class ServiceEntry(
    val label: String,
    val component: String, // flattened pkg/cls
    val packageName: String,
    val summary: String,
)

data class AppEntry(val label: String, val packageName: String)

object DeviceStore {

    /**
     * Apps that declare REQUEST_INSTALL_PACKAGES in their manifest.
     * getInstalledPackages(GET_PERMISSIONS) can exceed the binder transaction
     * limit on app-heavy devices and return nothing, so query per-package.
     */
    suspend fun installRequesters(context: Context): List<AppEntry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(0).asSequence()
                .map { it.packageName }
                .mapNotNull { p ->
                    runCatching {
                        val pi = pm.getPackageInfo(p, PackageManager.GET_PERMISSIONS)
                        if (pi.requestedPermissions?.any {
                                it == android.Manifest.permission.REQUEST_INSTALL_PACKAGES
                            } == true
                        ) {
                            AppEntry(
                                label = pm.getApplicationLabel(
                                    pi.applicationInfo ?: return@mapNotNull null
                                ).toString(),
                                packageName = p,
                            )
                        } else null
                    }.getOrNull()
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }

    /** Resolves display labels for package names (cheap, binder per call). */
    suspend fun labelsFor(context: Context, packages: List<String>): Map<String, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            packages.associateWith { pkg ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull() ?: pkg
            }
        }
    fun enabledA11y(context: Context): Set<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun installedA11y(context: Context): List<ServiceEntry> {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        return am.getInstalledAccessibilityServiceList()
            .map { info ->
                val ri = info.resolveInfo
                val si = ri.serviceInfo
                ServiceEntry(
                    label = ri.loadLabel(pm)?.toString() ?: si.name,
                    component = ComponentName(si.packageName, si.name).flattenToString(),
                    packageName = si.packageName,
                    summary = pm.getApplicationLabel(si.applicationInfo).toString(),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun enabledNotifListeners(context: Context): Set<String> =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun installedNotifListeners(context: Context): List<ServiceEntry> {
        val pm = context.packageManager
        val intent = Intent(NotificationListenerService.SERVICE_INTERFACE)
        return pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            .map { ri ->
                val si = ri.serviceInfo
                ServiceEntry(
                    label = ri.loadLabel(pm)?.toString() ?: si.name,
                    component = ComponentName(si.packageName, si.name).flattenToString(),
                    packageName = si.packageName,
                    summary = pm.getApplicationLabel(si.applicationInfo).toString(),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
