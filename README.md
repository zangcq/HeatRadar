# 热源雷达 (HeatRadar)

一款面向 Android 设备的实时资源监控工具，帮助你快速定位手机发热、卡顿、耗电异常的元凶应用。

## 功能特性

- **实时 CPU/内存排行**：实时展示各应用的 CPU 占用和物理内存占用 (RSS)
- **设备状态概览**：CPU 使用率、CPU 频率、内存占用、电池温度
- **多进程聚合**：同一包名的多进程（如 `:push`、`:appbrand`）自动聚合为一个条目
- **系统进程过滤**：默认隐藏低资源占用的系统进程，可一键切换显示
- **应用详情**：单个应用的 CPU、内存、活跃时长等详细信息
- **异常提醒**：自动识别高资源占用应用并给出提示
- **设置持久化**：所有偏好设置通过 DataStore 持久化，重启不丢失
- **多数据源支持**：轻量级守护进程（推荐）/ Shizuku / UsageStats 自动降级

## 截图

<div style="display: flex; gap: 16px; flex-wrap: wrap;">
  <img src="screenshots/dashboard.png" alt="热源雷达主页" width="280"/>
  <img src="screenshots/settings.png" alt="设置页" width="280"/>
</div>

- **主页（热源雷达）**：实时设备状态 + 应用资源占用排行，支持系统进程过滤
- **设置页**：守护进程控制、显示选项、采样设置、数据管理

## 系统要求

- **最低版本**：Android 10 (API 29)
- **目标版本**：Android 15 (API 35)
- **架构**：Kotlin + Jetpack Compose + MVVM

## 获取真实数据的方式

由于 Android 系统的沙箱限制，普通应用无法直接读取其他进程的 CPU 和内存数据。HeatRadar 支持三种数据源，按优先级自动选择：

### 1. 轻量级守护进程（推荐，无需安装额外应用）

通过一条 ADB 命令启动内置的 shell 守护进程（daemon），daemon 以 shell 权限在后台持续运行（每 2 秒执行一次 `top`），将所有进程的 CPU/内存数据写入 App 可读取的文件。无需安装 Shizuku 等第三方应用。

**使用步骤：**

1. 安装并打开 HeatRadar
2. 首页会显示"启动数据采集服务"引导卡片
3. 点击卡片上的**复制命令**按钮，复制 ADB 启动命令
4. 手机通过 USB 连接电脑，在终端中粘贴并执行复制的命令（仅需执行一次）
5. 返回 App，会自动检测到 daemon 运行，随即开始展示所有应用的真实数据

**启动命令示例：**

```bash
adb shell "sh -c 'cp /sdcard/Android/data/com.example.heatradar/files/heat_daemon.sh /data/local/tmp/heat_daemon.sh 2>/dev/null; chmod 755 /data/local/tmp/heat_daemon.sh 2>/dev/null; nohup sh /data/local/tmp/heat_daemon.sh >/dev/null 2>&1 &'"
```

**停止 daemon：**

在 App 设置页可以停止 daemon，或执行以下 ADB 命令：

```bash
adb shell "sh -c 'PID=$(cat /sdcard/Android/data/com.example.heatradar/files/daemon.pid 2>/dev/null); if [ -n "$PID" ]; then kill $PID 2>/dev/null; fi; pkill -f heat_daemon.sh 2>/dev/null; rm -f /sdcard/Android/data/com.example.heatradar/files/heat_daemon.sh /sdcard/Android/data/com.example.heatradar/files/daemon.pid /data/local/tmp/heat_daemon.sh 2>/dev/null; echo done'"
```

**注意事项：**
- 设备重启后 daemon 会停止，需重新执行一次 ADB 命令
- daemon 资源占用极低（shell 脚本 + 每 2 秒一次 top），几乎不影响续航
- 如果 daemon 异常退出，App 会自动降级到其他数据源

### 2. Shizuku（备选方案）

Shizuku 允许应用以 ADB/shell 权限直接执行系统命令，无需后台进程。适合不愿使用 ADB 命令但愿意安装 Shizuku 应用的用户。

**安装步骤：**

1. 下载安装 [Shizuku](https://github.com/RikkaApps/Shizuku/releases)（也可在酷安搜索"Shizuku"）
2. 启动 Shizuku 服务（以下方式任选其一）：
   - **无线调试（Android 11+，推荐）**：开发者选项 → 开启"无线调试" → Shizuku 应用中点击"启动" → 按提示配对
   - **USB 连接电脑**：执行以下命令
     ```bash
     # 启动 Shizuku 服务（路径可能因设备和版本不同，以 Shizuku 应用中"通过连接电脑启动"显示的命令为准）
     adb shell /data/app/~~*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so
     ```
3. 首次使用需在 Shizuku 弹窗中授权 HeatRadar
4. 重启设备后需重新启动 Shizuku 服务

### 3. UsageStats（降级方案，功能受限）

无需额外安装，但仅能获取：
- 应用前台使用时长（需用户手动在系统设置中授权"使用情况访问权限"）
- 自身进程的 CPU/内存数据
- 无法获取其他应用的实时 CPU 和内存

**授权方式**：系统设置 → 应用管理 → 特殊应用权限 → 使用情况访问 → 允许 HeatRadar

## 技术栈

| 类别 | 技术 | 说明 |
|---|---|---|
| 开发语言 | Kotlin | Android 主流开发语言 |
| UI 框架 | Jetpack Compose | 声明式 UI |
| 架构模式 | MVVM | Repository + ViewModel |
| 异步处理 | Kotlin Coroutines | 采样循环与数据查询 |
| 本地数据库 | Room | 采样记录、设备状态持久化 |
| 依赖注入 | Hilt | 管理全局依赖 |
| 页面导航 | Navigation Compose | 页面路由 |
| 设置持久化 | DataStore Preferences | 用户偏好设置持久化 |
| 图标加载 | 自研异步加载（produceState + PackageManager） | IO 线程加载应用图标，避免主线程阻塞 |
| 跨进程 | Shizuku UserService (AIDL) | 备选方案，通过 Shizuku 以 shell 权限执行命令 |
| 轻量级守护进程 | Shell 脚本 (heat_daemon.sh) | 推荐方案，内置 shell 脚本后台采集数据 |
| CPU 采集 | /sys/devices/system/cpu/cpu*/cpufreq/stats/time_in_state | 基于频率时间数据的双采样算法 |
| 进程扫描 | `top -n 1 -b -q` | 获取所有进程的 CPU% 和 RSS |

## 项目结构

```
app/src/main/java/com/example/heatradar/
├── app/                          # 应用入口
│   ├── HeatRadarApplication.kt   # Hilt 应用类
│   └── MainActivity.kt           # 主 Activity + Navigation
├── core/
│   ├── common/                   # 公共工具
│   ├── database/                 # Room 数据库层
│   │   ├── AppDatabase.kt        # 数据库定义
│   │   ├── HeatRadarRepository.kt # 数据仓库
│   │   └── *Entity.kt / *Dao.kt  # 实体与 DAO
│   ├── monitor/                  # 数据采集核心
│   │   ├── RealSampler.kt        # 真实采样器（3秒间隔主循环）
│   │   ├── FakeSampler.kt        # 假数据生成器
│   │   ├── ProcessScanner.kt     # 进程扫描（Daemon/Shizuku/UsageStats 三级降级）
│   │   ├── DaemonManager.kt      # 轻量级守护进程管理（部署、状态检测、命令生成）
│   │   ├── DeviceStateProvider.kt # 设备状态（CPU/内存/温度）
│   │   ├── AppInfoProvider.kt    # 已安装应用列表（带缓存）
│   │   ├── ForegroundAppProvider.kt # 前台应用检测
│   │   ├── ShizukuServiceManager.kt # Shizuku 连接管理
│   │   └── CommandService.kt     # Shizuku UserService 实现（AIDL）
└── feature/                      # 功能页面
    ├── dashboard/                # 首页 - 设备状态 + CPU/内存 Top 榜单 + daemon 引导卡片
    ├── appdetail/                # 应用详情页
    └── settings/                 # 设置页
```

app/src/main/assets/
└── heat_daemon.sh                # 轻量级守护进程 shell 脚本（内置到 APK）

## 数据源优先级

ProcessScanner 在每次扫描时按以下优先级自动选择数据源：

1. **Daemon**（`source=daemon`）：**推荐方案**，50-80+ 个应用，实时 CPU% + RSS 内存，无需安装额外应用
2. **Shizuku**（`source=shizuku`）：70+ 个应用，实时 CPU% + RSS 内存，功能完整
3. **UsageStats**（`source=usagestats`）：仅前台时长，无实时 CPU/内存
4. **System API**（`source=system`）：仅可见进程（通常只有自身）
5. **Self**（`source=self`）：保底，至少展示自身进程

## 性能优化

- **CPU 归一化**：多核设备上 `top` 输出的 CPU% 可能超过 100%，自动除以核心数转换为 0-100% 范围
- **应用列表缓存**：已安装应用列表缓存 5 分钟，避免每次扫描都调用 `pm.getInstalledApplications`
- **数据定期清理**：自动清理超过 7 天的历史采样数据，避免数据库无限增长
- **前后台采样控制**：应用进入后台时自动降低采样频率，减少电量消耗
- **Daemon 中转写入**：先写入 `/data/local/tmp/` 临时文件再复制到 App 目录，避免直接写入外部存储时的 I/O 问题

## 构建与安装

### 前置条件

- Android Studio Ladybug 或更高版本
- JDK 17
- Android SDK 35
- Gradle 8.9

### 构建 Debug APK

```bash
./gradlew assembleDebug
# 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

### 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 或者部分华为设备需要：
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/heatradar.apk && adb shell pm install -r /data/local/tmp/heatradar.apk
```

### 构建 Release APK

```bash
./gradlew assembleRelease
```

## 已知限制

- **Android 10+ 沙箱限制**：普通应用无法通过 `/proc` 直接读取其他进程信息，必须依赖守护进程或 Shizuku
- **SELinux 策略**：部分设备的 SELinux 策略可能影响数据采集，目前已在华为设备上验证通过
- **Daemon 重启失效**：设备重启后守护进程会停止，需重新执行一次 ADB 启动命令
- **Daemon 省电策略**：部分厂商系统（如 MIUI）可能对后台进程较激进，如发现 daemon 频繁被杀可在系统设置中关闭对 HeatRadar 的电池优化
- **内存数据**：Daemon/Shizuku 方案获取的是 RSS（常驻内存），System API 获取的是 PSS（比例分配内存），两者口径略有差异
- **CPU 百分比**：`top` 命令的 CPU% 基于单次采样，已做归一化处理（除以核心数）
- **UsageStats 权限**：特殊权限需用户在系统设置中手动开启，无法通过代码自动申请

## 调试技巧

### 查看实时日志

```bash
adb logcat -s ProcessScanner:* RealSampler:* DeviceStateProvider:* DaemonManager:* ShizukuServiceManager:*
```

### 验证 Daemon 是否工作

1. 检查 daemon 进程是否运行：
   ```bash
   adb shell "ps -A | grep heat_daemon"
   ```
2. 检查输出文件是否在更新：
   ```bash
   adb shell "ls -la /sdcard/Android/data/com.example.heatradar/files/top_output.txt"
   ```
3. 检查 daemon 状态：
   ```bash
   adb shell "cat /sdcard/Android/data/com.example.heatradar/files/daemon.status"
   ```

日志中查找 `daemonReady=true` 和 `source=daemon`，正常应解析 50-80 个应用进程。

### 验证 Shizuku 是否工作

日志中查找 `shizukuReady=true` 和 `source=shizuku`。

### 验证数据采集

```bash
# 查看扫描到的进程数和 Top CPU 应用
adb logcat -d -s ProcessScanner:* | grep "scanAllProcesses\|Top by CPU"
```

### 手动启动/停止 Daemon

```bash
# 启动
adb shell "sh /data/local/tmp/heat_daemon.sh &"

# 停止
adb shell "pkill -f heat_daemon.sh"
```

## 许可证

（待确定）

## 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供 ADB 权限执行能力，作为备选数据源方案
