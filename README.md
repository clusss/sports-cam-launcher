# sports-cam-launcher
一个面向 vivo X Flip 外屏（682×422 / 422×682）的 Jetpack Compose 启动器：把外屏改造成“运动相机”风格的桌面，
包含应用启动、文件夹、壁纸、定时计划、私密应用、最近删除、截屏/录屏等功能。

> ⚠️ 本项目涉及较多隐私相关权限（无障碍、修改系统设置、Shizuku、所有文件访问、麦克风、悬浮窗等），
> 全部能力均在本机实现，不上传任何数据。请仅从本仓库获取安装包，防止被篡改导致隐私泄露。

## 功能

- 运动相机风格桌面：应用图标、小组件、文件夹（2×2 窗口、多页）、横竖屏布局自适应
- 应用列表 / 私密应用（每次进入需密码）/ 最近删除 / 相册 / 视频
- 壁纸：自定义图片（缩放/旋转/裁剪）+ 一键恢复默认
- 截屏 / 录屏（Shizuku 通道，可用麦克风，录完自动合并音轨）
- 控制台：悬浮窗模式、定时计划（按时间自动开关运动相机模式）、启动器密码（含四个保护范围）
- 通知栏方向开关、保活通知、状态栏常驻隐藏

## 构建

需要 JDK 17 + Android SDK（compileSdk 34）+ Gradle 8.9：

```bash
cd sports-camera-launcher
./gradlew assembleDebug     # 生成 app/build/outputs/apk/debug/app-debug.apk
```

> 默认使用 **debug 签名**。要发布正式版本，请在 `app/build.gradle` 中配置你自己的 `signingConfigs`
> （见仓库 README 的“签名与版本”一节），不要提交任何 keystore 到仓库。

## 兼容性

- 主要适配：vivo X Flip（V2256A，Android 14），外屏分辨率 682×422 / 422×682
- minSdk 29 / targetSdk 29（低 targetSdk 是外屏覆盖窗行为的前提，请勿随意提高）
- 其它机型的副屏行为未适配

## 签名与版本

- `applicationId`: `cn.pashiguoke.subscreen`
- `versionName` / `versionCode`：见 `app/build.gradle`
- 仓库**不包含**发布用 keystore；正式包请自行生成并妥善保管（丢失将无法覆盖升级）

## 免责声明

本项目为个人免费开源项目，按「现状」提供，与 vivo / iQOO 官方无关。
请勿用于任何商业或非法用途；修改系统设置存在风险，由此产生的一切后果由使用者自行承担。
完整免责声明与用户守则见应用内「关于 → 免责声明与用户守则」。

## 许可

本项目采用 MIT 许可证，详见 LICENSE。

