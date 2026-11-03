# BiliRoaming_next

基于原版哔哩漫游，适配新版哔哩哔哩，并提供 LSPatch 版本给无 root 用户。

用户可于 [Releases](../../releases) 页面下载。

> 这是个人修改版，并非官方版本。使用前请阅读文末的说明与免责声明。

## 功能

- 解除番剧区域限制（需在设置中配置解析服务器）
- 广告过滤：首页 banner、视频页推广/推荐、暂停广告等
- 视频全屏/半屏清晰度设置
- 部分界面净化与功能增强
- 提供免 root 的 LSPatch 整合包

## 下载

前往 [Releases](../../releases) 页面：

| 文件 | 说明 |
| --- | --- |
| `*_noroot.apk` | LSPatch 整合版，**无需 root**，也无需安装 LSPatch 管理器 |
| `BiliRoaming_*.apk` | Xposed 模块，供 root / LSPosed 用户使用 |

支持的客户端：哔哩哔哩 Android **9.8.0**（`tv.danmaku.bili`，versionCode `9080300`）。

> 免 root 版与官方版签名不同，安装前请先卸载官方哔哩哔哩或旧版整合包，否则会提示签名冲突。

## 使用说明

1. 安装后打开哔哩哔哩，进入「哔哩漫游设置」。
2. 打开主功能开关，并按需配置解析服务器。
3. 其余功能（广告过滤、清晰度等）可在设置中调整，部分选项需重启客户端生效。

## 反馈与问题

遇到 bug 或有功能建议，欢迎在本仓库的 Issues 中反馈。提交问题时请尽量附上：客户端版本、模块版本、复现步骤，以及日志（`Android/data/tv.danmaku.bili/cache/log.txt`）。

## 致谢

- [yujincheng08/BiliRoaming](https://github.com/yujincheng08/BiliRoaming)：原版项目
- [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)：免 root 方案
- 以及所有上游依赖与贡献者

## 许可证

本项目代码派生自 BiliRoaming，遵循 **GNU General Public License v3.0 (GPL-3.0)**，详见 [LICENSE](LICENSE)。

## 免责声明

本项目仅供学习与技术研究使用，请勿用于商业用途。请勿在哔哩哔哩站内或任何公开场合宣传本项目。使用本项目的风险由使用者自行承担。
