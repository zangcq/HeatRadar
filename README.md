# 热源雷达 (HeatRadar)

一款面向 Android 设备的实时资源监控工具，帮助你快速定位手机发热、卡顿、耗电异常的元凶应用。

## 功能特性

- **实时 CPU 排行**：展示当前 CPU 占用最高的应用，采样间隔 3 秒
- **实时内存排行**：展示各应用的实际物理内存占用 (RSS/PSS)
- **设备状态概览**：CPU 使用率、CPU 频率、内存占用、电池温度
- **应用详情**：单个应用的 CPU、内存、活跃时长等详细信息
- **趋势记录**：历史采样数据存储，支持查看资源占用趋势
- **异常提醒**：自动识别高资源占用应用并给出提示
- **多数据源支持**：Shizuku（推荐）/ ADB 后台进程 / UsageStats 自动降级

## 截图

（待补充）

## 系统要求

- **最低版本**：Android 10 (API 29)
- **目标版本**：Android 15 (API 35)
- **架构**：Kotlin + Jetpack Compose + MVVM

## 获取真实数据的方式

由于 Android 系统的沙箱限制，普通应用无法直接读取其他进程的 CPU 和内存数据。HeatRadar 支持三种数据源，自动降级：

### 1. Shizuku（推荐，完整功能）

Shizuku 允许应用以 ADB/shell 权限执行系统命令，可获取所有进程的真实 CPU% 和内存数据。

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
4. 重启设备后需重新启动 Shizuku 服务（无线调试方式只需在 Shizuku 中点一下"启动"）

### 2. ADB 后台进程（开发/调试用）

通过 adb 启动后台 `top` 进程持续写入数据文件：

```bash
adb shell "nohup sh -c 'while true; do top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME > /sdcard/Android/data/com.example.heatradar/files/top_output.txt 2>&1; sleep 3; done' >/dev/null 2>&1 &"
```

设备重启后需重新执行。

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
| 跨进程 | Shizuku UserService (AIDL) | 通过 Shizuku 以 shell 权限执行命令 |
| CPU 采集 | /sys/devices/system/cpu/cpu*/cpufreq/stats/time_in_state | 基于频率时间数据的双采样算法 |
| 进程扫描 | Shizuku/ADB 执行 `top -n 1 -b -q` | 获取所有进程的 CPU% 和 RSS |

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
│   │   ├── ProcessScanner.kt     # 进程扫描（Shizuku/TopFile/UsageStats 三级降级）
│   │   ├── DeviceStateProvider.kt # 设备状态（CPU/内存/温度）
│   │   ├── AppInfoProvider.kt    # 已安装应用列表
│   │   ├── ForegroundAppProvider.kt # 前台应用检测
│   │   ├── ShizukuServiceManager.kt # Shizuku 连接管理
│   │   └── CommandService.kt     # Shizuku UserService 实现（AIDL）
│   └── ui/theme/                 # Compose 主题
└── feature/                      # 功能页面
    ├── dashboard/                # 首页 - CPU/内存 Top 榜单
    ├── appdetail/                # 应用详情页
    ├── trends/                   # 趋势页
    └── settings/                 # 设置页
```

## 数据源优先级

ProcessScanner 在每次扫描时按以下优先级选择数据源：

1. **Shizuku**（`source=shizuku`）：80+ 个应用，实时 CPU% + RSS 内存，最全数据
2. **ADB TopFile**（`source=topfile`）：70+ 个应用，读取后台 top 进程写入的文件
3. **UsageStats**（`source=usagestats`）：仅前台时长，无实时 CPU/内存
4. **System API**（`source=system`）：仅可见进程（通常只有自身）
5. **Self**（`source=self`）：保底，至少展示自身进程

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
```

### 构建 Release APK

```bash
./gradlew assembleRelease
```

## 已知限制

- **Android 10+ 沙箱限制**：普通应用无法通过 `/proc` 直接读取其他进程信息，必须依赖 Shizuku 或 ADB
- **SELinux 策略**：部分设备（如 Google Pixel、Huawei）的 SELinux 策略更严格，可能阻止某些数据采集
- **Shizuku 重启失效**：设备重启后 Shizuku 服务需要重新启动（无线调试方式最快，点一下即可）
- **ADB 后台进程**：可能被 MIUI 等系统的省电策略杀掉，应用检测到文件超过 30 秒未更新会自动降级
- **内存数据**：Shizuku/TopFile 方案获取的是 RSS（常驻内存），System API 获取的是 PSS（比例分配内存），两者口径略有差异
- **CPU 百分比**：`top` 命令的 CPU% 基于单次采样，多核设备上单进程可能超过 100%

## 调试技巧

### 查看实时日志

```bash
adb logcat -s ProcessScanner:* RealSampler:* DeviceStateProvider:* ShizukuServiceManager:*
```

### 验证 Shizuku 是否工作

日志中查找 `shizukuReady=true` 和 `source=shizuku`，正常应解析 70-90 个应用进程。

### 验证数据采集

```bash
# 查看扫描到的进程数和 Top CPU 应用
adb logcat -d -s ProcessScanner:* | grep "scanAllProcesses\|Top by CPU"
```

## 许可证

（待确定）

## 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供 ADB 权限执行能力，是获取真实跨进程数据的核心依赖
