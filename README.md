# BT 下载（Android）

一个自用的 Android BT 下载工具：支持磁力链接与 `.torrent` 文件，自带后台下载与应用内更新。

> 本项目仅用于下载你有权获取的内容。BT 协议本身合法，但下载受版权保护的作品可能违法。

## 功能

- **添加任务**
  - 粘贴磁力链接（支持 40 位 hex / 32 位 base32 的 `btih`）
  - 从系统文件选择器添加 `.torrent`
  - 在其他 App 中打开 `magnet:` 链接可直接唤起本应用并添加
- **任务管理**：暂停 / 继续 / 删除（可选是否同时删除已下载文件）
- **进度展示**：进度条、百分比、上下行速度、节点数
- **元数据阶段可视**：磁力链获取元数据时显示已等待时间与超时剩余秒数
- **后台下载**：前台服务保持下载，切后台或锁屏不中断
- **重启恢复**：任务列表与种子副本持久化，重启后自动重新加入
- **在线更新**：从 GitHub Releases 检查新版本，应用内下载并调起系统安装器
- **安全检查**：拒绝包含 `..` 或绝对路径的种子文件，种子大小限制 10MB

## 下载安装

最新 APK 见 [Releases](https://github.com/Canefx/torrent-android/releases/latest)：

```
https://github.com/Canefx/torrent-android/releases/latest/download/BtDownloader.apk
```

首次安装需在系统中允许“安装未知来源应用”。

## 使用

1. 打开应用，粘贴磁力链接或点“选择种子”
2. 磁力链需要先通过 DHT / tracker 获取元数据，最长等待 3 分钟
3. 下载中可暂停、继续或删除任务
4. 有新版本时，启动会自动提示，也可点“检查更新”

下载文件保存在应用外部文件目录：

```
Android/data/com.example.btdownloader/files/downloads
```

## 技术实现

| 部分 | 说明 |
|---|---|
| 下载引擎 | [libtorrent4j](https://github.com/aldenml/libtorrent4j)（libtorrent 的 Java/Android 绑定） |
| 后台运行 | 前台服务 + 通知 |
| 持久化 | 种子副本存于 `filesDir/torrents`，任务列表存于 SharedPreferences |
| 界面 | 纯代码构建的卡片式布局，无第三方 UI 依赖 |
| 构建 | Gradle + GitHub Actions，推送 `main` 自动编译并发布 Release |

### 目录结构

```
app/src/main/java/com/example/btdownloader/
├── MainActivity.kt      # 界面与交互
├── DownloadService.kt   # 前台服务，持有下载引擎
├── TorrentEngine.kt     # libtorrent4j 封装、持久化、安全检查
└── UpdateChecker.kt     # 在线更新：版本检查、下载、调起安装器
```

### 在线更新的实现方式

不使用 `api.github.com`（匿名配额仅 60 次/小时，共用出口 IP 时极易被限流），而是走 GitHub 的稳定下载路径：

```
https://github.com/Canefx/torrent-android/releases/latest/download/version.json
https://github.com/Canefx/torrent-android/releases/latest/download/BtDownloader.apk
```

CI 每次发布都会上传固定命名的 `BtDownloader.apk` 与 `version.json`，因此 `latest/download` 永远指向最新版本。`version.json` 格式：

```json
{
  "versionCode": 4,
  "versionName": "0.3.0",
  "apk": "BtDownloader.apk",
  "changelog": "更新说明"
}
```

## 本地构建

需要 JDK 17 与 Android SDK 34：

```bash
gradle :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

也可以在 GitHub Actions 页面手动触发构建，产物在 Artifacts 中下载。

## 发版流程

1. 修改 `app/build.gradle.kts` 中的 `versionCode`（**必须递增**）和 `versionName`
2. 推送到 `main`
3. CI 自动编译并创建 `v<versionName>` 的 Release
4. 已安装的旧版本在启动时即可收到更新提示

## 已知限制

- 暂停状态只在本次运行内记录，重启后任务按正常状态恢复
- 元数据获取阶段无法显示节点数（libtorrent4j 的阻塞接口限制）
- 不支持文件级选择、限速、做种规则
- 当前为 debug 签名，更换签名后需卸载重装
- 未在真机上验证完整下载流程与安装器调起
