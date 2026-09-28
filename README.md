# EhViewer@Lab
<img width="575" height="387" alt="image" src="https://github.com/user-attachments/assets/2a9145fc-cf1a-4be5-ad93-f0b461bba05d" />

一个基于原版EhViewer对HomeLab环境更为友好的Ehviewer
> [!NOTE]  
> EhViewer是一个Android平台的E-Hentai/ExHentai浏览器<br>
> EhViewer@Lab is not affiliated with E-Hentai.org in any way.

## 本项目在和原装绿E的不同之处在哪里
EhViewer@Lab在原版EhViewer的基础上，为 HomeLab/自托管环境持续提供更Geek的解决方案。本项目的初衷是希望能把本子快速的, 有效的, 无需用户手动操作地存进NAS里, 我是赛博仓鼠: 我坚信网络上的一切都是暂时的, 只有我NAS里的硬盘不会背叛我(除非它坏了)

#### 已实现
- [x] 支持 SMB NAS 存储
- [x] 画廊直达NAS
- [x] 内网阅读
- [x] 内网搜索
- [x] 自动下载到NAS
- [x] 内网画廊管理
- [x] 自动适配/检测最优网络线程数
- [x] 多设备共享同一网络储存,并共享阅读进度

#### 未来会实现
这些也就是我想要些什么罢了, 如果你有更加好的Idea, 请务必在issue里提出, 任何idea都欢迎
- [ ] NFS NAS储存
- [ ] 基于HomeLab环境算力的图片优化,如超分
- [ ] 基于HomeLab环境算力的图片翻译
- [ ] 基于HomeLab环境下的图片去码(或许有)
- [ ] 可调整的自动下载策略

## 版本策略 
本项目是基于杰神(xiaojieonly)二次开发而来的下游版本, 所以版本基线和[https://github.com/xiaojieonly/Ehviewer_CN_SXJ](绿E)一致, 并在末尾标注 `-hl.N`
例如 `2.0.2.3-hl.1` = 基于上游 2.0.2.3 的第 1 个 fork 迭代。

### 上游版本更新
本项目会不定期的吸取上游版本更新, merge 上游后基线版本随之更新、后缀归 1

至于什么时候我会吸取上游的更新取决于我的精力(一般为一到两周内), 当然如果是紧急情况, 例如E绅士改变HTML结构导致的parse失效的问题, 我会立刻吸收任何pending change. 如果有必要: 我会在这个fork中修复导致无法使用的错误并对upstream提出PR

### 项目的分离
如果出现杰神不再对他的repo进行维护的情况, 或者是本项目实在是走的太远无力吸取上游的情况, 本项目将会从杰神的绿E中彻底脱离


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
