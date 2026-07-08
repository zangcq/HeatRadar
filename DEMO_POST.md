# 【生活娱乐赛道】HeatRadar 热源雷达 —— Android 设备发热/卡顿元凶定位工具

---

## 1. Demo 简介

**是什么：** HeatRadar（热源雷达）是一款面向 Android 设备的**实时资源监控 App**，帮助你快速定位手机发热、卡顿、耗电异常的元凶应用。

**面向谁：** 所有 Android 手机用户，尤其是：
- 经常感觉手机莫名发热、卡顿、耗电快的用户
- 想知道哪个 App 在后台偷偷跑高负载的极客玩家
- 需要排查应用性能问题的开发者

**主要功能：**

- **实时设备状态概览**：CPU 使用率、CPU 频率、内存占用、电池温度一目了然
- **应用资源占用排行**：实时展示各应用的 CPU% 和物理内存占用(RSS)，精准定位"发热源"
- **多进程智能聚合**：同一应用的多进程（如微信 `:push`、`:appbrand`）自动合并，CPU 累加、内存取最大值，避免重复条目
- **系统进程过滤**：默认隐藏低资源占用的系统进程，一键切换显示全部进程
- **轻量级守护进程方案**：内置 shell 脚本，通过一条 ADB 命令即可获取全进程真实数据，**无需安装 Shizuku 或 Root**
- **异常提醒**：自动检测高资源占用应用并标记警告
- **数据持久化**：所有设置通过 DataStore 持久化，重启不丢失

**产品截图：**

![主页 - 实时设备状态 + 应用资源占用排行](screenshots/dashboard.png)
![设置页 - 守护进程控制与偏好设置](screenshots/settings.png)

---

## 2. Demo 创作思路

**灵感来源：** 夏天使用 Android 手机时经常遇到手机莫名发热、电量飞速下降的情况，想知道到底是哪个 App 在作祟，但发现：
- 系统自带的"电池使用情况"只能看到耗电排行，且更新滞后，看不到实时 CPU/内存数据
- 市面上的监控工具（如 DevCheck、CPU Monitor 等）要么需要 Root，要么需要安装 Shizuku，要么广告太多
- 大多数工具没有做进程聚合，微信、抖音等多进程 App 会显示好几条记录，根本看不出到底是哪个在耗资源

**想解决的问题：**
1. **普通用户看不懂的技术门槛**：需要 Root 或 Shizuku 才能获取真实数据，把普通用户挡在门外
2. **多进程应用数据分散**：微信/抖音等 App 开了多个进程，现有工具把它们分开显示，用户看不出总占用
3. **系统进程噪音太多**：几十上百个系统进程混在列表里，普通用户根本找不到自己安装的 App

**为什么做这个方向：**
- Android 发热/卡顿是普遍痛点，但现有工具要么太重要么太技术化
- 想到可以用**内置 shell 守护进程**的方案：App 自带一个 `heat_daemon.sh` 脚本，用户只需执行一条 ADB 命令，daemon 就以 shell 权限在后台每 2 秒执行一次 `top`，将结果写入 App 可读的文件——既不需要 Root，也不需要安装额外应用
- 用 Jetpack Compose 做现代化的 Material3 UI，交互简洁直观，普通用户也能看懂

---

## 3. Demo 体验地址

- **GitHub 源码仓库**：https://github.com/zangcq/HeatRadar
- **APK 下载**：从源码编译，执行 `./gradlew assembleDebug` 即可生成 Debug APK
- **在线体验**：Android 应用需安装到设备体验，无 Web 版本

**快速体验步骤（约 2 分钟）：**
1. 克隆项目并编译安装：`./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 打开 App，点击首页"复制命令"按钮复制 ADB 启动命令
3. 手机 USB 连接电脑，终端中粘贴执行复制的命令（仅需一次）
4. 返回 App 即可看到实时 CPU/内存排行

---

## 4. TRAE 实践过程

HeatRadar 从 0 到 1 的完整开发过程均使用 **TRAE IDE** 完成。开发过程遵循"AI 规划 → AI 编码 → 人工验收 → AI 修复"的协作模式。

### 开发阶段概览

**第一阶段：项目初始化与架构搭建**
- 提示 TRAE 创建 Android 项目，选定 Kotlin + Jetpack Compose + MVVM + Hilt + Room 技术栈
- 设计数据模型：RunningProcess、DeviceStateSnapshot、ResourceSample 等核心实体
- 搭建基础导航框架：Bottom Navigation + Navigation Compose
- 实现基础 UI：Dashboard 首页列表、Scaffold + TopAppBar + LazyColumn

**第二阶段：核心数据采集能力（P0）**
- 实现三级数据源降级机制：Daemon（推荐）→ Shizuku → UsageStats → System API → Self 保底
- 编写内置 shell 守护进程脚本 `heat_daemon.sh`，实现自动部署到 `/data/local/tmp/`
- 实现 CPU 频率/使用率采集：读取 `/sys/devices/system/cpu/cpu*/cpufreq/stats/time_in_state`，双采样算法计算实时 CPU%
- 实现进程扫描：解析 `top -n 1 -b -q` 输出，支持多核 CPU% 归一化（除以核心数转为 0-100%）
- 实现内存采集：读取 `/proc/meminfo` 获取总内存/可用内存
- 实现温度采集：读取 `/sys/class/thermal/thermal_zone*/temp`
- 应用图标加载：从 PackageManager 获取应用图标，使用 Coil 异步加载（后优化为 produceState）

**第三阶段：体验优化（P1）**
- 设置持久化：引入 DataStore Preferences，替代内存临时状态，重启不丢失
- 多进程聚合：同一包名的进程 CPU% 累加、内存取最大值，避免微信/抖音显示多条
- 系统进程过滤：默认隐藏 CPU<1% 且内存<50MB 的系统进程，FilterChip 一键切换
- 图标异步加载优化：改用 `produceState` 在 IO 线程加载图标，解决 Coil 无法加载跨应用图标的问题
- 设置页完善：守护进程状态卡片（显示 PID、启停控制）、显示选项、采样设置、数据管理（清除所有数据）

**第四阶段：UI 打磨与品牌设计**
- 应用 Logo 设计：深蓝底色 + 白色雷达同心圆 + 橙色热源点的矢量 Adaptive Icon，呼应"热雷达"主题
- 优化系统进程开关 UI：从过大的 Switch 改为紧凑的 Material3 FilterChip
- 移除冗余的"趋势"页：简化底部导航为"热源雷达"+"设置"两个 Tab
- 修复设置页无法滚动的 Bug：添加 verticalScroll，确保底部内容可见
- 更新 README 文档，补充截图和使用说明

### 关键技术决策（由 TRAE 辅助完成）

| 问题 | TRAE 给出的方案 | 最终选择 |
|------|----------------|----------|
| 如何在无 Root 情况下获取全进程数据？ | 对比 Shizuku/AIDL/UsageStats/Shell Daemon 四种方案 | 内置 Shell Daemon（推荐）+ Shizuku（备选） |
| 多进程应用重复显示？ | 按包名聚合，CPU 累加、内存取 max | 已实现 |
| 系统进程列表太吵？ | 默认过滤低占用系统进程，提供开关 | 已实现，FilterChip 切换 |
| 应用图标加载卡顿？ | produceState + Dispatchers.IO 异步加载 | 已实现，替代 Coil |
| 设置重启丢失？ | DataStore Preferences 持久化 | 已实现 |

### 踩坑记录

1. **Coil 无法加载其他应用图标**：`android.resource://` URI 只能加载自身资源，跨应用图标需通过 PackageManager.getApplicationIcon 获取
2. **Shizuku UserService 连接不稳定**：部分设备上 Shizuku 服务频繁断开，作为备选方案保留，主推更轻量的 daemon 方案
3. **华为设备 ADB install 限制**：部分华为设备不支持直接 `adb install`，需先 push 到 `/data/local/tmp/` 再 `pm install`
4. **SELinux 策略差异**：不同厂商设备的 SELinux 策略不同，daemon 写入路径需先写临时文件再复制到 App 目录
5. **CPU% 超过 100%**：多核设备上 `top` 输出的 CPU% 是累加值，需除以核心数归一化到 0-100%

---

**📋 发帖前需要补充的内容（由你手动填写）：**

1. **Session ID（至少 3 个）**：在 TRAE 中双击对话列表中的关键会话，复制 Session ID 填入。建议选取以下关键阶段的 Session：
   - 项目初始化/架构搭建阶段
     - 2226923911517127:6464ec4da20224c6b1662db006f3f93a_6a472c080b608880790fe19a.6a472c080b608880790fe19d.6a472c080b608880790fe19b:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/3 11:27:04)
   - P0 核心数据采集功能开发阶段
     - 2226923911517127:3cd6d422d08b0dc113b32a68c3871563_6a472c080b608880790fe19a.6a477a480b608880790fe42f.6a477a470b608880790fe42d:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/3 17:00:56)
   - P1 优化/UI 打磨阶段
     - 2226923911517127:95f671035a962e2433d246295216f45a_6a472c080b608880790fe19a.6a488c6d0b608880790fe8fa.6a488c6d0b608880790fe8f8:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/4 12:30:37)
   
2. **开发关键步骤截图（至少 3 张）**：在 TRAE IDE 中截取以下画面：
   - TRAE 中与 AI 对话规划架构的截图
   
     ![image-20260708125937789](/Users/zangchuanqi/Library/Application Support/typora-user-images/image-20260708125937789.png)

   - TRAE 中 AI 生成代码/修复 Bug 的截图
   
     ![image-20260708125537276](/Users/zangchuanqi/Library/Application Support/typora-user-images/image-20260708125537276.png)
   
   - 项目文件结构/代码编辑界面的截图
   
   - ![image-20260708125442573](/Users/zangchuanqi/Library/Application Support/typora-user-images/image-20260708125442573.png)
   
3. **报名帖链接**：你通过审核的社区报名帖链接

4. **赛道确认**：当前选择的是「生活娱乐」赛道，请确认与你报名时的赛道一致（可选：生活娱乐 / 学习工作 / 社会服务 / 硬件交互）
