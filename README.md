# EhViewer@Lab

一个基于原版EhViewer对HomeLab环境更为友好的Ehviewer

> EhViewer@Lab is not affiliated with E-Hentai.org in any way.

在原版 EhViewer 的基础上，为 HomeLab / 自托管环境持续提供更Geek的解决方案。

### 支持 SMB NAS 存储
画廊直达NAS, 实现内网阅读, 内网搜索, 自动下载到NAS, 内网画廊管理等功能


## 版本策略 

`<上游版本基地>-hl.<N>`，例如 `2.0.2.3-hl.1` = 基于上游 2.0.2.3 的第 1 个 fork 迭代。
merge 上游后基底随之更新、后缀归 1。

## 构建 
```sh
./gradlew :app:assembleAppReleaseDebug
./gradlew :app:testAppReleaseDebugUnitTest
```

## 发版

需要 [Bun](https://bun.sh)（Windows / Linux / macOS 均可）。

```sh
# 1. 提版本：hl.N +1，并同步更新源 feedauthor/update.json
bun script/bump-version.ts --notes "更新说明一" --notes "更新说明二"
#    合并了上游后改用：bun script/bump-version.ts --base <上游版本>

# 2. review + 提交 + 合并到 main

# 3. 在 main 上打 tag 触发签名发布（脚本会先做一致性与同步检查）
bun script/release.ts
```

`release.ts` 会校验：工作区干净、在 main 且与远端同步、update.json 与
build.gradle 版本一致、tag 未存在——全部通过才推 tag，由 CI 出签名 APK
挂到 GitHub Release。两个脚本都支持 `--dry-run`。


## 特别鸣谢 
本项目是基于 [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) 的二次开发, 没他就没我

## Credits & License

- 上游：[xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)（作者 SXJ_LonelyDog，"用爱发电，快乐前行"）
- 原始项目：seven332/EhViewer
- 许可证：Apache-2.0（见 [LICENSE](LICENSE)，第三方声明见 NOTICE）

问题反馈：[Issues](https://github.com/HicirTech/Ehviewer-Lab/issues)
