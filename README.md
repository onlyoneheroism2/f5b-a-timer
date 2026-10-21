# F5B A科目计时器

原生 Android 计时应用，提供 F5B A 科目比赛预设和可保存的自定义分段计时方案。

## 获取和安装

- 安装包：[`release/F5B-A-Timer-v1.2.apk`](release/F5B-A-Timer-v1.2.apk)
- 将 APK 下载或传到 Android 手机，在文件管理器中打开，并按系统提示允许安装。
- 最低支持 Android 6.0（API 23）。中文播报依赖手机系统的中文 TTS 语音数据。

## 自定义方案

在主界面切换到“自定义”并打开“方案设置”。可添加多个时段，每段可设置名称、按秒输入的时长、正计时或倒计时，以及按秒设置的播报间隔。比如只计时 10 秒，时长直接填 `10`；间隔设为 `1` 秒即逐秒报时。设置保存在应用私有配置中。

正计时段从 00:00 起显示并播报已过时长；倒计时段从该段总时长起显示，并按设定间隔播报剩余时长。时段按列表顺序连续运行，整套方案结束时播报“时间到”并停止。改动方案后计时器会重置。

F5B 预设规则、计时精度和播报时序见[技术说明](docs/TECHNICAL.md)。

## 从源码构建

使用 Android Studio 打开项目并同步 Gradle，或在已安装 JDK 17 和 Android SDK 35 的环境中执行：

```powershell
.\gradlew.bat assembleDebug
```

构建使用 Android Gradle Plugin 8.7.3 和 Gradle 8.9。默认 `assembleDebug` 生成的 APK 使用 Android 默认 debug 签名，适合侧载和分享测试；发布到应用商店前应配置个人 release 签名密钥。
