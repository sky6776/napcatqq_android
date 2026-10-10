# 第三方组件声明

此私人侧载构建内置了完整离线运行时，其中包含 Debian、Linux QQ、NapCatQQ 及全部必需的系统依赖库。运行时由 `tools/runtime-build.sh` 在构建时从各官方源拉取并组装，不随源码分发；实际使用的版本与校验值记录在 `runtime-packages/manifest.json` 与 `runtime-packages/build-info.json`。打包内容不含 QQ 账号数据、NapCat 配置、凭据、日志、缓存、SSH 数据及 Shell 历史记录。

运行时组件的官方来源：

| 组件 | 来源 | 默认版本（current profile） |
| --- | --- | --- |
| Debian ARM64 rootfs | <https://deb.debian.org/debian>（mmdebstrap 生成） | trixie（Debian 13） |
| Linux QQ `.deb` (arm64) | <https://qqdl.gtimg.cn/qqfile/QQNT/>（腾讯官方 CDN，社区镜像 `Rodert/qq-versions` 兜底） | 3.2.32-52194 |
| NapCatQQ | <https://github.com/NapNeko/NapCatQQ/releases> | 4.18.35 |
| Xvfb 及系统依赖 | Debian 官方仓库 | 随 trixie |

APK 内含下列 ARM64 宿主工具，文件未经修改，取自 Termux 官方软件包仓库：

| 组件 | 软件包版本 | 上游软件包 SHA-256 |
| --- | --- | --- |
| PRoot 与加载器 | `5.1.107.85` | `103e210d9e2ddc2b7b18147753dafccc775470ec88204f96a080534b36da261f` |
| libandroid-shmem | `0.7` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |
| libtalloc | `2.4.3` | `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da` |

源代码与许可证信息：

- PRoot：<https://github.com/termux/proot>，GPL-2.0
- libandroid-shmem：<https://github.com/termux/libandroid-shmem>
- talloc：<https://talloc.samba.org/talloc/doc/html/index.html>，LGPL-3.0-or-later
- Termux 软件包：<https://github.com/termux/termux-packages>
- Apache Commons Compress：<https://commons.apache.org/proper/commons-compress/>
- XZ for Java：<https://tukaani.org/xz/java.html>

可复现的精确下载与解包流程位于 `tools/fetch-host-tools.ps1`。

NapCatQQ 与腾讯 QQ 分别适用其自身的许可证和服务条款。此制品仅用于用户的私人侧载部署；未取得必要许可前，请勿公开发布或重新分发 APK 或运行时归档。
