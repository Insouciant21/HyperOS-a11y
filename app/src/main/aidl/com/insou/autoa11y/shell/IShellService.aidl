package com.insou.autoa11y.shell;

interface IShellService {
    // Append/remove the component to/from enabled_accessibility_services (atomic
    // read-modify-write inside the shell process); returns the resulting list.
    String setA11yEnabled(String component, boolean enable) = 1;

    // cmd notification allow_listener/disallow_listener; returns resulting
    // enabled_notification_listeners value.
    String setNotificationListener(String component, boolean enable) = 2;

    // Streams the apk through a fd into /data/local/tmp then pm install.
    // flags bit0=-r bit1=-t bit2=-d bit3=-g bit4=--bypass-low-target-sdk-block
    String installApk(in ParcelFileDescriptor apk, int flags) = 3;

    String getAppOps(String pkg, String op) = 4;

    String setAppOps(String pkg, String op, String mode) = 5;

    // SAI-style bundle (.apks/.apkm/.xapk): zip containing base.apk + split_*.apk.
    // Installs via install-create / install-write xN / install-commit.
    String installSplits(in ParcelFileDescriptor bundleZip, int flags) = 6;

    // appops query-op <op> <mode>: one package name per line.
    String queryAppOps(String op, String mode) = 7;

    void destroy() = 16777114;
}
