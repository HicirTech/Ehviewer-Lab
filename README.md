# EhViewer@Lab

一个基于原版EhViewer对HomeLab环境更为友好的Ehviewer

> EhViewer@Lab is not affiliated with E-Hentai.org in any way.

在原版 EhViewer 的基础上，为 HomeLab / 自托管环境持续提供更Geek的解决方案。

### 支持SMB Nas存储
画廊直达Na, 实现内网阅读, 内网搜索, 自动下载到NAS, 内网画廊管理等功能


## 版本策略 

`<上游版本基地>-hl.<N>`，例如 `2.0.2.3-hl.1` = 基于上游 2.0.2.3 的第 1 个 fork 迭代。
merge 上游后基底随之更新、后缀归 1。

## 构建 
```sh
./gradlew :app:assembleAppReleaseDebug
./gradlew :app:testAppReleaseDebugUnitTest
```


## 特别鸣谢 
本项目是基于 [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) 的二次开发, 没他就没我

## Credits & License

- 上游：[xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)（作者 SXJ_LonelyDog，"用爱发电，快乐前行"）
- 原始项目：seven332/EhViewer
- 许可证：Apache-2.0（见 [LICENSE](LICENSE)，第三方声明见 NOTICE）

问题反馈：[Issues](https://github.com/HicirTech/Ehviewer_With_SMB_Saver/issues)
