# NapCatHost

NapCatHost 是为 NapCatQQ 制作的免 Root Android 前台运行宿主，可独立于 Termux 运行。APK 已内置 Debian、全部系统依赖、Xvfb、Linux QQ、NapCatQQ 和 ARM64 PRoot；安装 APK 后无需再下载运行组件。

当前版本针对 vivo V1911A（Android 10 / API 29、ARM64）制作，仅供私人侧载使用。

## 快速开始

### 方式一：双击一键构建（推荐）

双击项目根目录的 [build.cmd](build.cmd)。脚本完成测试、Lint 和 APK 构建后，会显示三个选项：

> 本地构建前需要先准备约 400 MiB 的离线运行时归档 `runtime-packages\napcathost-runtime-arm64-v1.tar.zst`（不入库）。新克隆的仓库请先按[从官方源构建离线运行时](#从官方源构建离线运行时)生成它，再运行 `build.cmd`；CI 构建会自动完成该步骤。

| 选项 | 作用 |
| --- | --- |
| `B` | 仅构建 APK |
| `I` | 构建后通过 Android Package Manager 会话直接安装 |
| `C` | 构建后复制到手机“下载”目录，并打开系统安装界面 |

也可以在 Windows CMD 或 PowerShell 中使用非交互模式：

```bat
build.cmd build
build.cmd install
build.cmd copy
```

最终 APK 固定输出到：

```text
dist\NapCatHost-offline-v0.2.0-debug.apk
```

SHA-256 校验文件会同步更新到 `dist\SHA256SUMS.txt`。

### 两种手机安装方式的区别

1. `build.cmd install`

   使用现有的 Package Manager 分块安装会话，适合 400 MiB 以上的大 APK。脚本会覆盖升级已有 NapCatHost，并保留应用配置和已经解压的运行时。vivo 弹出 USB 安装确认时，需要在手机上选择允许。

2. `build.cmd copy`

   把 APK 复制到手机的：

   ```text
   内部存储/Download/NapCatHost-offline-v0.2.0-debug.apk
   ```

   脚本随后尝试打开 Android 系统安装界面。如果系统没有自动打开，请解锁手机，在“文件管理 → 下载”中点击 APK 完成安装。首次使用这种方式时，Android 可能要求为文件管理器开启“允许安装未知应用”。

> 安装或升级 APK 不会重新下载任何依赖。覆盖安装会保留应用数据；卸载应用则会同时删除已解压的运行时、账号数据和 NapCat 配置。

## 首次使用

1. 安装并打开 NapCatHost。
   首页标题下方会显示当前版本名称和构建号，可用于确认是否已经升级到最新 APK。
2. 点击“初始化内置运行时”。应用会校验并解压 APK 中的完整离线包，约需 1.4 GiB 内部存储空间，全程不访问软件仓库。初始化成功后该按钮会变为灰色且不可再次点击；需要修复时请使用“修复离线运行时启动文件”。
3. 授予忽略电池优化权限。
4. 在 vivo 的应用管理中，为 NapCatHost 开启“高耗电后台运行”和“自启动”。
5. 根据需要填写 QQ 号。可选择“安全保存 QQ 密码并自动登录”，也可不启用并继续使用二维码登录。
6. 开启“开机后自动恢复前台服务”。“锁屏增强保活”默认开启，会每 10 分钟通过系统闹钟短暂唤醒 CPU，并在 Wi-Fi 下保持无线网络高性能模式；代价是增加耗电。
7. 点击“启动”。通知栏出现 NapCatHost 常驻通知后，即可返回桌面或熄屏。
8. 点击“打开 WebUI”进入 `http://127.0.0.1:6099/webui`。应用会自动附带 NapCat 最近生成的私有令牌，无需从日志手工复制。

如果手机上仍运行着 Termux 版 NapCat，请先停止它，否则默认的 `6099` 或 `3001` 端口可能发生冲突。

## QQ 密码自动登录

NapCatHost 使用 NapCat WebUI 提供的 `/QQLogin/PasswordLogin` 接口完成密码登录，处理流程与 WebUI 的“密码登录”选项一致：

1. 在“账号与恢复”中填写 QQ 号。
2. 勾选“安全保存 QQ 密码并自动登录”。
3. 输入密码并点击“保存配置”或“启动”。
4. NapCat WebUI 就绪后，应用会先检查当前登录状态；仅在未登录时提交密码登录请求。

安全处理方式：

- 明文密码仅在输入和保存时短暂存在于 Android 进程内存中。
- 应用按照 NapCat WebUI 的行为计算 QQ 密码 MD5，再使用 Android Keystore 中不可导出的 AES-256-GCM 密钥加密。
- 应用偏好设置中只保存密文和随机 IV，不保存明文密码。
- 密码及密码摘要不会写入日志，也不会传入 PRoot 命令行或环境变量。
- 主界面启用 Android `FLAG_SECURE`，系统截图和最近任务预览不会记录密码输入界面。
- 取消勾选并保存配置会删除已保存的密码密文。

QQ 可能要求滑块验证码、风控验证或新设备确认。出现这类情况时，NapCatHost 会保持前台运行并提示“请打开 WebUI”；需要在 WebUI 的“密码登录”页面完成腾讯安全验证。验证完成并产生本地登录会话后，后续通常可直接快速登录。

## 日常使用

- “启动”：保存当前 QQ 配置并启动 PRoot、Xvfb、Linux QQ 与 NapCat。
- “停止”：停止受监管的 QQ/NapCat 进程并撤销运行期 WakeLock。
- “修复离线运行时启动文件”：保留账号数据和配置，仅重新生成启动文件，不访问网络。
- “导入 rootfs”：导入自行准备的 tar、tar.gz、tar.bz2、tar.xz 或 tar.zst 运行时包。
- 常驻通知提供快速启动/停止操作。

应用使用前台服务、`START_STICKY`、Partial WakeLock 和进程退出指数退避重启。增强保活启用时，CPU 锁使用 20 分钟有界租约，并由 10 分钟周期闹钟强制释放后重新获取；这能修复部分 vivo 系统已经撤销内核锁、应用进程却仍误认为 `isHeld()` 为真的情况。界面会显示最近一次 CPU 保活锁续租时间和结果。

“锁屏增强保活”会使用 `AlarmManager.setAlarmClock()` 每 10 分钟重新进入一次前台服务，强制续租 CPU 锁、检查受监管进程并触发必要的登录状态恢复。该闹钟只唤醒 CPU，不会点亮屏幕，但 Android 会长期显示“下次闹钟”标识；这是启用增强保活后的预期行为。若厂商系统拒绝闹钟级调度，应用会退回 `setAndAllowWhileIdle()`。建议同时开启电池白名单、高耗电后台运行和自启动。

前台服务还会主动向 Android 请求维持一条非 VPN 的物理 Internet 网络，同时监听网络断开、恢复和 Wi-Fi/移动网络切换，并每 3 分钟执行一次本地健康检查。检查内容包括监管进程、NapCat WebUI 6099 端口和 QQ 登录状态。网络短暂抖动只会延迟重试；WebUI 连续 3 次无响应才会自愈重启 Linux QQ/NapCat。QQ 未登录也需要间隔 20 秒连续确认 2 次，避免 Linux QQ 启动阶段误报；确认掉线后，已保存密码的账号会尝试自动登录，并在 75 秒后复核。仍未恢复时会重启 Linux QQ/NapCat，使其在当前默认网络上重新建立全部连接。需要验证码或新设备验证时不会自动重启，避免打断人工验证。

启用密码自动登录后，NapCatHost 不会与 Linux QQ 同时发起两种登录。它会先等待 Linux QQ 快速登录最多 20 秒；快速登录成功就跳过密码，只有 NapCat 返回明确登录错误或等待超时后仍未在线，才会提交沙箱中保存的密码。密码提交前还会再次确认状态；如果此时 QQ 恰好已经在线，则直接按成功处理。健康检查确认在线时也会清除先前遗留的“自动登录未完成”或“掉线”提示。

## 手工构建与安装

如不使用 `build.cmd`，可直接运行 PowerShell 脚本。本机禁用 PowerShell 脚本执行时，请保留以下 `ExecutionPolicy Bypass` 参数：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\install-device.ps1
```

项目默认使用 `E:\my-android\android-toolchain` 中的 JDK 17、Android SDK、Gradle 和 ADB。设置环境变量 `NAPCATHOST_TOOLCHAIN` 可改用其他同布局的工具链根目录，`build.cmd`、`tools\build.ps1` 和 `tools\install-device.ps1` 均读取该变量。

## 从官方源构建离线运行时

离线运行时归档（约 400 MiB）不入库。除 APK 内置的 PRoot 宿主工具（`proot`、`loader`、`libandroid-shmem.so`、`libtalloc.so.2`，取自 Termux 官方软件包仓库，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）外，仓库不含其他二进制；Debian、Linux QQ、NapCatQQ 等运行时组件全部在构建时由脚本从各官方源拉取并组装：

| 组件 | 来源 |
| --- | --- |
| Debian ARM64 rootfs | `deb.debian.org` 官方镜像（mmdebstrap 生成） |
| Linux QQ `.deb` (arm64) | 腾讯官方 CDN `qqdl.gtimg.cn`（社区镜像 `Rodert/qq-versions` 兜底） |
| NapCatQQ | GitHub Releases `NapNeko/NapCatQQ` |
| Xvfb 及系统依赖 | Debian 官方仓库 |

所有下载均带 SHA-256 校验。组件版本在 `tools/runtime-build.sh` 顶部钉死。

### 方式一：GitHub Actions（推荐，无需本机环境）

1. 把仓库推到 GitHub 后，打开仓库页面 → **Actions** 标签页。
2. 左侧选择 **Build offline APK** 工作流，点右侧 **Run workflow** 按钮，选择分支和运行时 profile（默认 `current`）后点击绿色按钮触发（推送代码到 `main`/`master` 分支也会自动触发）。
3. GitHub 的 Ubuntu 服务器会自动完成：安装工具链 → 拉包组装运行时 → 同步校验和 → 构建 APK。
4. 构建成功后会自动发布滚动更新的 Release（固定 tag 为 `latest`，每次成功构建覆盖上一次的附件）：包含 APK、`SHA256SUMS.txt`、运行时归档及 `manifest.json`、`build-info.json`，Release 说明中列出组件版本和归档校验值。到仓库的 **Releases** 页面下载即可，`releases/latest/download/<文件名>` 形式的链接始终指向最新构建，无需推送版本 tag。
5. 当次运行的 **Artifacts**（`NapCatHost-offline-apk`、`napcathost-runtime-archive`）仍会保留，默认 90 天后过期；Release 附件不会过期。
6. 首次构建约需 30–60 分钟（Debian rootfs 与 QQ 依赖在 qemu 模拟下安装较慢）。公开仓库 Actions 免费时长不限；私有仓库消耗免费额度。注意：公开仓库的 Release 附件任何人无需登录即可下载（私有仓库仅协作者可见），等同于公开分发内嵌 QQ 的 APK；如需遵守下文的分发限制，请将仓库设为私有。

### 方式二：本机 WSL2

要求：WSL2 中装有 Debian/Ubuntu 发行版。一次性准备（在发行版内以 root 执行）：

```bash
apt-get install -y mmdebstrap qemu-user-static proot zstd xz-utils unzip curl python3
```

然后在 Windows 侧执行（脚本会自动经 WSL 组装、回填 `manifest.json` 并同步 `RuntimeInstaller.java` 中的校验和）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build-runtime-from-scratch.ps1
```

构建完成后直接运行 `build.cmd` 打出包含新运行时的 APK。

### 版本组合（profile）

- `current`（默认）：QQ `3.2.32-52194` + NapCatQQ `4.18.35`，即 NapCat 官方安装器当前钉定的配对，所有组件当前均可从官方源下载。注意：此组合未经真机验证，首次使用请在 WebUI 完整走一遍登录流程。
- `device`：复刻现网验证过的 QQ `3.2.25-45758` + NapCatQQ `4.18.9`。腾讯 CDN 已下架该版本，需自备安装包：`-QqDeb <本地deb路径>` 或 `-QqTarZst <旧运行时归档路径>`（从中提取 QQ 文件）。

切换方式：本机运行 `build-runtime-from-scratch.ps1 -Profile device`；CI 在 **Run workflow** 时把 `profile` 选为 `device`，并在 `qq_deb_url` 填入 QQ arm64 `.deb` 的下载地址（工作流会先下载该文件再构建）。该入口下载的 `.deb` 不做强制 SHA-256 校验，请自行确认文件来源可靠。

## 架构

```text
Android 前台服务（START_STICKY + Partial WakeLock）
  -> 带指数退避重启的进程监管器
    -> 内置 ARM64 PRoot
      -> Debian ARM64 rootfs
        -> Xvfb
          -> Linux QQ / NTQQ
            -> NapCatQQ
```

内置离线归档由构建脚本从官方源生成，不入库。每次运行时构建（WSL 或 CI）都会随归档重写 `runtime-packages/manifest.json` 与 `runtime-packages/build-info.json`，记录实际大小、SHA-256 和组件版本；CI 构建的这两份文件随运行时归档 Artifact 一并上传：

```text
runtime-packages/napcathost-runtime-arm64-v1.tar.zst
```

打包快照已经排除 QQ 登录数据、NapCat 配置、令牌、日志、缓存、SSH 数据及 Shell 历史记录。

## 常见问题

### 构建脚本提示找不到工具

确认下列文件存在（或设置环境变量 `NAPCATHOST_TOOLCHAIN` 指向同布局的其他工具链根目录）：

```text
E:\my-android\android-toolchain\jdk\jdk-17.0.19+10
E:\my-android\android-toolchain\gradle\gradle-9.1.0\bin\gradle.bat
E:\my-android\android-toolchain\sdk\platform-tools\adb.exe
```

### ADB 显示设备未授权

解锁手机，接受 USB 调试授权，然后重新运行脚本。可用以下命令确认：

```bat
E:\my-android\android-toolchain\sdk\platform-tools\adb.exe devices
```

### vivo 拒绝 USB 安装

在开发者选项中开启“USB 安装”，保持手机解锁，并在安装确认界面选择允许。也可以改用 `build.cmd copy`，复制后从手机文件管理器安装。

### WebUI 无法打开

确认界面状态为“NapCat 运行中”，并检查最近日志中是否已经出现 WebUI 6099 端口。如果 Termux 版 NapCat 仍在运行，请先停止它再重启 NapCatHost。

### Android 杀死应用后能否恢复

普通后台限制下，前台服务和 WakeLock 可显著提高存活率；进程异常退出时监管器会自动重启。用户主动执行“强行停止”后，任何免 Root 应用都无法自行恢复，需要重新打开 NapCatHost。

## 重要限制

- 应用有意将目标 API 设为 28，使 Android 10 允许执行应用私有目录中的 ARM64 二进制文件。此设计仅适用于目标设备上的私人侧载。
- 当前实现仅支持 ARM64。
- APK 约 412 MiB，解压后的运行时还需要约 1.4 GiB 内部存储空间。
- 开机恢复还依赖设备厂商的自启动权限。
- 内置的 NapCatQQ 与腾讯 QQ 文件分别受其自身许可证和服务条款约束。未经许可，不得公开分发此私人构建。

第三方组件来源、许可证和校验值请参阅 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
