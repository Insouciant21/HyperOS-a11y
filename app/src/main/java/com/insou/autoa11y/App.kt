package com.insou.autoa11y

import android.app.Application
import com.insou.autoa11y.shell.A11yWatchdog
import com.insou.autoa11y.shell.ShellGateway

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ShellGateway.init(this)
        A11yWatchdog.init(this)
    }
}
