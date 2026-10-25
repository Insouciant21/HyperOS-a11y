# A11yKit

基于 [Shizuku](https://shizuku.rikka.app/) 的 Android 权限工具箱：无障碍服务管理（含自愈保护）、静默安装器、通知使用权与未知安装权全设备管理。UI 使用 [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（HyperOS 风格 Compose 组件库）+ 底部导航栏液态玻璃（miuix-blur 渐进模糊）。

## 功能

- **无障碍**：全设备服务列表与开关（shell 原子读-改-写 `enabled_accessibility_services`，绕过 MIUI/HyperOS 十秒盾与受限设置）；被系统自动关闭后 ~1s 内自动恢复（看门狗，10 分钟窗口限流 3 次，连续 3 败自动放弃）
- **安装**：SAF 选择 `.apk / .apks / .apkm / .xapk / .apk.1`，按内容识别格式（非扩展名）；单 APK 走 `pm install`，拆分包走 `install-create/write/commit` 多会话；xapk 的 OBB 自动解到 `Android/obb/<pkg>/`；加密 apkm 识别并提示不支持；五项安装选项（-r/-t/-d/-g/绕过低 targetSdk 拦截）
- **权限**：通知使用权（`cmd notification allow_listener`）与未知应用安装权（`appops REQUEST_INSTALL_PACKAGES`，`query-op` 批量拉取状态）全设备列表开关

## 前提

设备已安装并运行 Shizuku（API 23+ 模式），且已授予本应用授权。所有特权操作在 Shizuku UserService（shell uid）中执行，App 自身不申请敏感权限（仅 `QUERY_ALL_PACKAGES`）。

## 构建工具链备忘

- **JDK 21（完整版，含 jlink）**：DevEco JBR 缺 jlink，JDK 26 的 jlink 无法转换 android-37 的 `core-for-system-modules.jar`。`gradle.properties` 中 `org.gradle.java.home` 需指向完整 JDK 21。
- **Gradle 9.4.1**（wrapper 已配置腾讯镜像 distributionUrl）；**AGP 9.1.1**：Kotlin 已内置，勿再应用 `org.jetbrains.kotlin.android`。
- **compileSdk 37**：平台目录须为 `android-37.0`（新命名）；`licenses/android-sdk-license` 需同时含 `d56f5187...` 与 `24333f8a...` 两行 hash，否则 AGP 陷入自动安装循环。
- 依赖走阿里云镜像（`settings.gradle.kts`），dl.google 不可达时无需改动。

## 结构

```
app/src/main/java/com/insou/autoa11y/
├─ shell/       # IShellService(UserService) / ShellGateway / A11yWatchdog
├─ data/        # 设备状态读取（免特权部分）
├─ installer/   # 内容识别与包信息解析
└─ ui/          # Miuix 三页 UI + 玻璃导航
```

AIDL 变更后需递增 `ShellGateway` 中 UserServiceArgs 的 `version`，Shizuku 才会重建 shell 服务进程。
