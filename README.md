# EhViewer@Lab

**A HomeLab-oriented EhViewer.**

在原版 EhViewer 的基础上，为 HomeLab / 自托管环境持续提供更 Geek 的解决方案。
SMB 网络存储是第一个例子——画廊直接存进你的 NAS，阅读、进度、断点续读全部走共享——后续还会有更多。

Based on [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)
(itself a continuation of seven332's EhViewer). This fork does not merge back;
it continuously absorbs upstream features while building its own on top.

> EhViewer@Lab is not affiliated with E-Hentai.org in any way.

## 与原版的区别 / What's different

| | 原版 EhViewer | EhViewer@Lab |
|---|---|---|
| 画廊存储 | 设备本地 | **SMB 网络共享（NAS）**，设备本地仍可用 |
| 阅读进度 / 断点 | 设备本地 | **跟随共享**——换设备、重装 app 都不丢 |
| 本地库存 | — | **Local Inventory**：直接浏览共享上的画廊，封面/页数/评分就地读取 |
| 下载模式 | 设备本地 | 可选 **SMB 直存**（含自动下载开关、完整性校验与跳过） |
| 包名 | `com.hippo.ehviewer` / `com.xjs.ehviewer` | `com.hicirtech.ehviewer` — **可与原版并装** |

## SMB workflow

1. 在 NAS 上准备一个 SMB 共享（实测参考：SSD 存储池 + SMB3）
2. 设置 → SMB 设置：填主机 / 共享名 / 路径 / 账号密码
3. 开启「保存到 SMB」；可选开启「自动下载」（读第一页时自动把画廊存进共享，已完整的自动跳过）
4. 侧边栏「Local Inventory」浏览共享上的画廊，点开即读

共享上的目录布局：`<gid>-<title>/` 内含页面图片（`00000001.webp…`）、`cover.webp`、
`metadata.json`（画廊元数据）与 `.ehviewer`（pToken + 阅读进度）。
**画廊数据与阅读状态天然存活于共享**——设备侧只有配置和登录会话。

## 版本策略 / Versioning

`<上游基底>-hl.<N>`，例如 `2.0.2.3-hl.1` = 基于上游 2.0.2.3 的第 1 个 fork 迭代。
merge 上游后基底随之更新、后缀归 1。

## 迁移说明（从 com.xjs.ehviewer 老包名）

换包名 = 全新安装，无法原地升级。SMB 用户的核心数据（画廊、进度、断点）都在共享上，
新安装填好同样的 SMB 配置即可无缝衔接；需要重做的只有 E-Hentai 登录。

## 构建 / Build

```sh
# JDK 21 · Android SDK 35 · NDK/CMake（AGP 自动获取）
./gradlew :app:assembleAppReleaseDebug
./gradlew :app:testAppReleaseDebugUnitTest
```

## Credits & License

- 上游：[xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)（作者 SXJ_LonelyDog，"用爱发电，快乐前行"）
- 原始项目：seven332/EhViewer
- 许可证：Apache-2.0（见 [LICENSE](LICENSE)，第三方声明见 NOTICE）

问题反馈：[Issues](https://github.com/HicirTech/Ehviewer_With_SMB_Saver/issues)
