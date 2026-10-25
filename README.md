# TV DY

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20TV%20Box-green.svg)](app/src/main/AndroidManifest.xml)
[![minSdk](https://img.shields.io/badge/minSdk-21-orange.svg)](app/build.gradle.kts)
[![targetSdk](https://img.shields.io/badge/targetSdk-35-orange.svg)](app/build.gradle.kts)

在 Android TV、电视盒子、投影仪上用遥控器刷抖音。

TV DY 采用 **原生 ExoPlayer 播放 + 隐藏 WebView 数据泵** 的混合架构：后台 WebView 加载 douyin.com 并拦截页面自带的 feed 接口响应，提取带有效签名的视频流地址；前台用 ExoPlayer 全屏播放，上下切换带 TikTok 式滑动动画，专为遥控器操作优化。

为缩短冷启动，feed 快照与视频字节会落盘缓存。「启动即播」快路径见下方[冷启动](#冷启动)一节。

> 本项目为第三方开源客户端，与抖音官方无任何关联。仅供学习交流，请遵守相关法律法规与平台服务条款。

---

## 功能特性

- **原生播放** — Media3 ExoPlayer 直链播放，预加载后续 5 条视频，弱网环境下缓冲策略针对 TV 优化
- **Feed 数据泵** — 隐藏 WebView 被动捕获页面 fetch/XHR 的 feed JSON，不自行伪造签名请求，分页由页面滚动触发
- **遥控器导航** — 上下切视频、左右快进/快退、OK 键播放/暂停与功能菜单
- **Cookie 登录** — 手机/电脑扫码提交浏览器 Cookie，无需在电视上输入长字符串
- **创作者作品** — 登录后可浏览当前视频作者的全部作品并点播
- **已看过滤** — 记录账号历史与本机播放过的视频，feed 缓冲中自动跳过
- **TV 适配** — 支持 Leanback Launcher；TV 设备启动超时自动延长；横屏全屏沉浸

---

## 架构概览

```mermaid
flowchart LR
    subgraph 后台
        WV[隐藏 WebView<br/>FeedPumpController]
        FR[FeedRepository]
        WV -->|拦截 feed JSON| FR
    end

    subgraph 前台
        PA[PlayerActivity]
        EP[ExoPlayer]
        PA --> EP
        FR -->|视频列表| PA
    end

    subgraph 登录
        CHS[CookieHandoffServer<br/>:8899]
        CIH[CookieImportHelper]
        CHS --> CIH --> WV
    end

    CDN[(douyinvod CDN)] --> EP
    WV -->|Cookie / 签名| CDN
```

| 模块 | 说明 |
|------|------|
| `FeedPumpController` | 隐藏 WebView，Hook fetch/XHR，捕获 feed 与分页数据 |
| `FeedRepository` | 视频缓冲队列，供播放器消费 |
| `PlayerActivity` | 唯一界面：播放、遥控器、菜单、登录 |
| `CookieHandoffServer` | 局域网 HTTP 服务，接收手机/PC 提交的 Cookie |
| `WatchedAwemeStore` | 按 session 持久化已看视频 ID |
| `CreatorVideoRepository` | 创作者主页作品列表 |

---

## 截图

| 启动加载 | 视频播放 |
|:---:|:---:|
| ![启动加载](docs/screenshots/loading.png) | ![视频播放](docs/screenshots/playback.png) |
| Feed 数据泵等待首条视频 | 标题、作者、进度条与互动数据 |

| 功能菜单 | 创作者作品 |
|:---:|:---:|
| ![功能菜单](docs/screenshots/menu.png) | ![创作者作品](docs/screenshots/creator-grid.png) |
| 长按 OK 打开，登录后可查看博主作品 | 4 列网格浏览作者全部视频 |

---

## 环境要求

| 项目 | 版本 |
|------|------|
| Android Studio | Ladybug 或更高（AGP 8.12+） |
| JDK | 11 |
| compileSdk / targetSdk | 35 |
| minSdk | 21（Android 5.0+） |
| 设备 | Android TV、电视盒子，或带遥控器的 Android 设备 |

---

## 构建与安装

```bash
# 克隆仓库
git clone https://github.com/Jie009/TikTokTV.git
cd TikTokTV

# 构建 Debug APK
./gradlew assembleDebug

# 安装到已连接设备（需开启 USB 调试或 adb 网络连接）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 下可使用 `gradlew.bat assembleDebug`。

Release 构建：

```bash
./gradlew assembleRelease
```

---

## 遥控器操作

### 播放界面

| 按键 | 功能 |
|------|------|
| **上 / 下** | 上一条 / 下一条视频 |
| **OK 短按** | 显示信息栏；播放中再次短按暂停；暂停时短按恢复 |
| **OK 长按** | 打开功能菜单 |
| **左 / 右** | 后退 / 快进 5 秒（按住连续 seek） |
| **返回** | 先关闭信息栏 / 菜单 / 登录页；连按两次退出应用 |
| **播放/暂停键** | 切换播放状态 |

播放时信息栏（标题、作者、进度、点赞/收藏/评论数）会在 3 秒后自动隐藏。

### 功能菜单

长按 OK 进入菜单，**上 / 下** 选择，**OK** 确认，**返回** 关闭。

| 菜单项 | 状态 | 说明 |
|--------|------|------|
| 登录账号 | ✅ | Cookie 扫码登录 |
| 创作者作品 | ✅ | 浏览当前作者的全部视频 |
| 退出登录 | ✅ | 清除 Cookie 并恢复匿名 feed |

---

## 登录说明

抖音网页登录带有滑块验证码，TV 端无法直接完成登录，因此采用 **Cookie 导入** 方式：

1. 在电脑或手机浏览器登录 [douyin.com](https://www.douyin.com)
2. 打开开发者工具 → Network，复制任意请求的完整 `Cookie` 请求头
3. 在 TV 上 **长按 OK** → 选择 **登录账号**
4. 用手机扫描屏幕上的二维码（需与 TV 在同一局域网）
5. 在打开的网页表单中粘贴 Cookie 并提交
6. App 会向抖音服务器验证 session 有效性，成功后自动刷新 feed

### ADB 脚本（开发调试）

项目提供了 PowerShell 脚本，可通过 adb reverse 直接向本机 `8899` 端口提交 Cookie：

```powershell
# 前提：App 已打开登录界面并等待提交
.\scripts\inject-cookie.ps1 -CookieFile .cursor\tmp_cookie.txt
```

---

## 项目结构

```
app/src/main/java/mulin/tvdy/
├── player/          # PlayerActivity、ExoPlayer、滑动切换、创作者网格
├── pump/            # FeedPumpController、XHR 拦截、分页状态
├── data/            # FeedRepository、FeedVideo、已看记录
├── auth/            # Cookie 导入、登录验证、QR 码、局域网 Handoff
├── api/             # Native feed 客户端与签名服务
├── feed/            # Feed 控制器抽象
├── DouyinConstants.java   # UA、Referer、请求头
├── DeviceUtils.java       # TV 设备检测
└── TvdyApplication.java   # WebView 预热
```

---

## 技术栈

- [AndroidX Media3 ExoPlayer](https://developer.android.com/media/media3) — 视频播放
- [AndroidX WebKit](https://developer.android.com/develop/ui/views/layout/webapps) — WebView 兼容层
- [ZXing](https://github.com/zxing/zxing) — 登录二维码生成
- [Lottie](https://airbnb.io/lottie/) — 启动与加载动画

---

## 冷启动

抖音网页版是一个体积极大的 SPA，页面启动脚本还被编译成字节码。实测在电视上从 `Application.onCreate` 到首帧约 33 秒，其中近 31 秒花在「等 WebView 把抖音页面跑起来、再由页面自己发出带签名的 feed 请求」——这部分不在本项目的控制范围内，无法通过改本项目的代码消除。

因此启动路径改为两级：

1. **快路径（命中缓存时）** — 启动时直接读取上次成功捕获的 feed JSON（`FeedSnapshotStore`，内部存储，上限 20 条 / 6 小时有效），立刻建出播放列表并起播，完全不等待 WebView。封面同样从磁盘读取（`CoverDiskCache`），视频字节走 ExoPlayer 磁盘缓存（`MediaCacheStore`，上限 200 MB），命中时不发网络请求。
2. **回退路径** — 快照不存在、已过期，或其签名链接已失效时，自动回到原有的 WebView 数据泵流程，行为与从前一致。

关于签名：抖音的 CDN 直链是短时效的（实测 6 天前的链接返回 `403 time expired`），每次抓取 feed 都会重新签发。所以媒体缓存不按完整 URL 作键，而是取 URL 中内容寻址的路径部分作键（见 `MediaCacheStore.canonicalKey`），使同一视频在不同会话中命中同一条缓存。

诊断时间线：

```bash
adb logcat -c && adb logcat -s StartupTrace:I
```

命中快路径时会出现 `startup: repository primed from snapshot n=…`；链接失效回退时会出现 `startup: snapshot abandoned after player error`。

---

## 常见问题

**启动后长时间停留在加载页？**  
Feed 数据泵需要 WebView 加载 douyin.com 并等待页面发起 feed 请求。TV 设备网络较慢时最长等待约 2 分钟，超时后可短按 OK 重试。登录状态下会优先打开「精选」页以加快 feed 触发。

**视频无法播放？**  
CDN 直链需要正确的 Referer 与 Cookie。请确认已登录且 Cookie 未过期；匿名模式下部分视频可能受限。

**登录二维码无法生成？**  
需确保 TV 已连接 Wi-Fi 或以太网，且手机与 TV 在同一局域网。

**点赞 / 评论 / 关注？**  
不做这些操作。播放页只展示抖音返回的点赞/收藏/评论数统计。

---

## 免责声明

- 本软件按「现状」提供，不提供任何明示或暗示的保证
- 使用本软件产生的任何后果由使用者自行承担
- 请尊重内容创作者版权，勿用于商业用途或大规模爬取
- 「抖音」及相关商标归字节跳动所有

---

## 开源协议

[MIT License](LICENSE) — Copyright (c) 2025 yemulin

---

## 致谢

- 参考了 [ygtec-org/douyin-tv](https://github.com/ygtec-org/douyin-tv) 等社区项目的 TV 适配思路
