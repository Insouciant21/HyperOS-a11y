package com.insou.autoa11y.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The row owns the action. A pending write blocks input without flashing gray. */
@Composable
internal fun StatusSwitch(checked: Boolean, busy: Boolean) {
    val colors = MiuixTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = !busy,
        colors = SwitchDefaults.switchColors(
            disabledCheckedThumbColor = colors.onPrimary,
            disabledUncheckedThumbColor = colors.onSecondary,
            disabledCheckedTrackColor = colors.primary,
            disabledUncheckedTrackColor = colors.secondary,
        ),
    )
}
