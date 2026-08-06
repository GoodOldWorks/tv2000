# TV2000 本地测试指南

| 项目 | 内容 |
| --- | --- |
| 适用版本 | MVP v0.1 及后续版本 |
| 配套计划 | [TV2000 MVP 开发与测试计划](./TV2000-MVP-DEVELOPMENT-TEST-PLAN.md) |
| 产品规格 | [TV2000 产品与技术规格说明书](./TV2000-SPEC.md) |
| 目标 | 在开发电脑、Android TV 模拟器和真实电视设备上完成可重复的本地验证 |

> 当前仓库只有规格文档，尚未创建 Android 工程。因此本文中的 Gradle、APK 和包名命令需要在工程初始化后执行。示例默认应用 ID 为 `com.tv2000.app`；若实际 ID 不同，应统一替换。

---

## 1. 本地测试的三层结构

TV2000 不能只使用模拟器测试。

| 层级 | 运行位置 | 能验证什么 | 不能替代什么 |
| --- | --- | --- | --- |
| L1 逻辑测试 | 开发电脑 JVM | 排序、频道编号、状态机、历史、扫描规则 | Android 生命周期、Media3、真实存储 |
| L2 模拟器测试 | Android TV AVD | UI、D-pad、Room、DataStore、SAF 主流程、前后台 | USB 物理插拔、真实 codec、HDR、真实性能 |
| L3 真机测试 | 电视盒子/电视整机 | U 盘、遥控器、硬件解码、休眠、首帧、内存 | 大规模自动化的执行效率 |

推荐日常节奏：

```text
每次代码修改
    ↓
L1 单元测试
    ↓
L2 模拟器冒烟
    ↓
当天可安装包
    ↓
至少一台 L3 真机冒烟
```

发布结论必须来自 L3 真机；L2 模拟器只用于缩短开发反馈周期。

---

## 2. 第一次准备环境

### 2.0 当前开发机检查结果

2026-07-30 检查结果：

| 项目 | 状态 |
| --- | --- |
| CPU | Apple Silicon / arm64 |
| macOS | 26.3 |
| Homebrew | 已安装 |
| Android Studio | 已安装 |
| Android Studio JBR | 已安装，OpenJDK 25 |
| ADB / Platform-Tools | 已安装，尚未加入 shell PATH |
| Android SDK Platform | API 37 |
| Android SDK Build Tools | 36.0.0 |
| Android TV 模拟器镜像 | 尚未安装 |
| 全局 Gradle | 未安装，且不需要安装 |

Android Studio 自带 JetBrains Runtime，可作为 Android 构建 JDK；项目应使用提交到仓库的 Gradle Wrapper，因此无需通过 Homebrew 安装 Java 或 Gradle。

### 2.1 开发电脑

从 Android Studio 的 `Tools > SDK Manager` 安装：

- Android SDK Platform 28；
- Android SDK Platform 37，作为当前 compile/target 基线；
- Android SDK Build-Tools；
- Android SDK Platform-Tools；
- Android SDK Command-line Tools（latest）；
- Android Emulator；
- Android TV API 28 ARM64 系统镜像（SDK Manager 提供时）；
- Android TV 或 Google TV API 37 ARM64 系统镜像。

Apple Silicon 应优先选择 `ARM 64 v8a` 系统镜像。如果 SDK Manager 不提供 API 28 的 ARM64 TV 镜像，不要下载 x86 镜像强行作为主环境；最低 API 28 应改用真机或 CI 验证，本地模拟器使用可获得的最低 ARM64 TV 镜像。

暂时不需要安装：

- 全局 Gradle；
- 单独的 Oracle JDK；
- Android NDK；
- CMake；
- FFmpeg；
- Docker；
- Node.js；
- libVLC。

确认 ADB 可用：

```bash
adb version
adb devices -l
```

确认 Java 和 Gradle 环境：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$JAVA_HOME/bin/java" -version
./gradlew --version
```

Android 工程创建后，应保证以下命令在干净 checkout 上可运行：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

### 2.2 创建两个 Android TV 模拟器

在 Android Studio 中：

1. 打开 `Tools > Device Manager`；
2. 选择 `Create Virtual Device`；
3. 选择 Android TV 设备配置；
4. 创建 `TV2000_API_28`；
5. 创建 `TV2000_CURRENT`，系统版本与项目 target API 对应；
6. 两个 AVD 均启用硬件图形加速；
7. 分别启动一次，完成系统初始化。

两个模拟器的用途：

- `TV2000_API_28`：最低系统兼容；
- `TV2000_CURRENT`：新权限、SAF 和生命周期兼容。

模拟器序列号可通过以下命令查看：

```bash
adb devices -l
```

为当前终端指定设备：

```bash
export TV2000_DEVICE=emulator-5554
export TV2000_PACKAGE=com.tv2000.app
```

不要假设序列号永远是 `emulator-5554`，应先查看 `adb devices -l`。

---

## 3. 测试数据准备

### 3.1 标准测试目录

在开发电脑建立不含版权风险的短视频测试集：

```text
testdata/
└── usb-basic/
    ├── 动画/
    │   ├── 1.mp4
    │   ├── 2.mp4
    │   └── 10.mp4
    ├── 西游记/
    │   ├── 第01集.mp4
    │   ├── 第01集.srt
    │   └── 第02集.mp4
    ├── BBC/
    │   ├── S01E01.mkv
    │   └── S01E02.mkv
    └── 错误测试/
        ├── 01-valid.mp4
        ├── 02-truncated.mp4
        ├── 03-wrong-extension.mp4
        └── 04-zero-byte.mp4
```

建议每个正常样本：

- 时长 60～120 秒；
- 画面中显示文件自身的名称；
- 每 10 秒有明显画面或声音变化；
- 不同频道使用不同背景色和音调；
- 使用自制或明确许可的内容；
- 保存 SHA-256，避免测试文件被意外替换。

画面显示文件名非常重要，否则切台、自然排序或历史串台时不容易肉眼识别。

### 3.2 性能测试目录

另建：

```text
testdata/
└── usb-1000/
    └── 性能频道/
        ├── 0001.mp4
        ├── 0002.mp4
        ├── ...
        └── 1000.mp4
```

这些文件必须是可读取的短媒体，不能使用零字节文件代替，因为生产扫描器会忽略零字节文件。

### 3.3 U 盘准备

至少准备：

- 一个 32GB FAT32 U 盘；
- 一个 64GB 或更大的 exFAT U 盘。

在 U 盘根目录创建 `TV2000`，再把 `usb-basic` 目录下的频道目录复制进去：

```text
USB_ROOT/
└── TV2000/
    ├── 动画/
    ├── 西游记/
    ├── BBC/
    └── 错误测试/
```

不要再额外保留一层 `usb-basic/`，否则它会被识别成唯一频道。另需验证删除或改名 `TV2000` 后，应用会回退扫描 U 盘根目录；也可在“资源管理 → U盘 → 修改”中测试自定义相对目录和留空使用根目录。

TV2000 只读 U 盘。应用测试不得格式化、删除或改写用户媒体。

---

## 4. Debug 构建需要的测试能力

生产版本只允许扫描可移除存储；模拟器通常没有真实 U 盘。因此工程必须提供仅存在于 `debug` source set 的测试适配层。

### 4.1 Debug StorageProvider

建议结构：

```text
app/src/main/       真实 StorageProvider
app/src/debug/      DebugStorageProvider、测试广播接收器
app/src/test/       纯逻辑测试
app/src/androidTest/仪器和 UI 测试
```

Debug StorageProvider 应允许：

- 在缺少系统 DocumentsUI 的 TV 镜像上，把应用私有外部目录
  `/sdcard/Android/data/com.tv2000.app/files/TV2000-Test` 当成虚拟 U 盘根目录；
- 模拟 storage inserted；
- 模拟 storage removed；
- 模拟授权撤销；
- 模拟扫描期间文件变化；
- 为每次测试指定稳定的 fake `volumeId`。

这些入口不得编译进 release 变体。

### 4.2 Debug 测试广播

建议仅在 debug Manifest 中注册包限定的测试动作：

```text
com.tv2000.debug.STORAGE_INSERTED
com.tv2000.debug.STORAGE_REMOVED
com.tv2000.debug.PERMISSION_REVOKED
com.tv2000.debug.FILE_CHANGED
```

调用示例：

```bash
adb -s "$TV2000_DEVICE" shell am broadcast \
  -a com.tv2000.debug.STORAGE_REMOVED \
  -p "$TV2000_PACKAGE"
```

恢复：

```bash
adb -s "$TV2000_DEVICE" shell am broadcast \
  -a com.tv2000.debug.STORAGE_INSERTED \
  -p "$TV2000_PACKAGE"
```

测试 Receiver 必须：

- 只存在于 debug 构建；
- 不修改真实文件；
- 只向应用内部测试状态机发送事件；
- release 构建检查中验证完全不存在。

### 4.3 可选虚拟外部卷

Android 官方提供以下命令，用于在没有可移除存储的设备上测试外部存储可用性逻辑：

```bash
adb -s "$TV2000_DEVICE" shell sm set-virtual-disk true
```

该能力依赖系统镜像，不能保证所有 AVD 或电视固件都可用，也不能替代真实 U 盘测试。使用后应在 Device Manager 中重启模拟器，并检查：

```bash
adb -s "$TV2000_DEVICE" shell sm list-volumes all
```

---

## 5. 在模拟器上测试

### 5.1 安装 Debug APK

构建：

```bash
./gradlew assembleDebug
```

安装：

```bash
adb -s "$TV2000_DEVICE" install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

如果使用 Android Studio，可以直接选择目标 AVD 并点击 Run。

### 5.2 推送测试数据

创建模拟 USB 根目录：

```bash
adb -s "$TV2000_DEVICE" shell mkdir -p \
  /sdcard/Android/data/com.tv2000.app/files/TV2000-Test
```

推送整个测试集：

```bash
adb -s "$TV2000_DEVICE" push \
  testdata/usb-basic/. \
  /sdcard/Android/data/com.tv2000.app/files/TV2000-Test/
```

确认文件：

```bash
adb -s "$TV2000_DEVICE" shell ls -R \
  /sdcard/Android/data/com.tv2000.app/files/TV2000-Test
```

如果 TV 镜像包含可用的 DocumentsUI，启动 Debug 构建后可选择
`/Download/TV2000-Test` 作为测试根目录。如果镜像只包含
`com.android.tv.frameworkpackagestubs`，Debug 构建会自动使用上述应用私有目录，
不会再调用无效的系统选择器。Release 构建不包含该回退行为。

### 5.3 模拟遥控器

| 操作 | ADB 命令 |
| --- | --- |
| 上 | `adb -s "$TV2000_DEVICE" shell input keyevent 19` |
| 下 | `adb -s "$TV2000_DEVICE" shell input keyevent 20` |
| 左 | `adb -s "$TV2000_DEVICE" shell input keyevent 21` |
| 右 | `adb -s "$TV2000_DEVICE" shell input keyevent 22` |
| OK | `adb -s "$TV2000_DEVICE" shell input keyevent 23` |
| Back | `adb -s "$TV2000_DEVICE" shell input keyevent 4` |
| Home | `adb -s "$TV2000_DEVICE" shell input keyevent 3` |

这组命令可以直接用于 UI Automator 测试或本地重复切台脚本。

快速双击使用两条相同的 KeyEvent，间隔需不超过 350ms：

```bash
# 右双击：当前频道下一集
adb -s "$TV2000_DEVICE" shell input keyevent 22
adb -s "$TV2000_DEVICE" shell input keyevent 22

# 左双击：当前位置 >5 秒时回到本集 00:00；当前位置 ≤5 秒时播放上一集
adb -s "$TV2000_DEVICE" shell input keyevent 21
adb -s "$TV2000_DEVICE" shell input keyevent 21

# 原生媒体键：下一集 / 上一集
adb -s "$TV2000_DEVICE" shell input keyevent 87
adb -s "$TV2000_DEVICE" shell input keyevent 88
```

验证时还应确认：

- 单击左右键在约 350ms 后仍分别快退 10 秒、快进 30 秒；
- 双击不会先执行一次 seek；
- 换集后保留原来的播放/暂停状态；
- 首集双击左、末集双击右只显示边界提示，不循环换集。

### 5.4 模拟冷启动

停止应用：

```bash
adb -s "$TV2000_DEVICE" shell am force-stop \
  "$TV2000_PACKAGE"
```

推荐由 Android Studio重新启动，或在 Activity 名称确定后使用：

```bash
adb -s "$TV2000_DEVICE" shell am start -W \
  -n "$TV2000_PACKAGE/.MainActivity"
```

验证：

- 没有首页；
- 直接恢复上次频道；
- Episode 正确；
- 位置误差不超过 1 秒；
- 播放/暂停状态符合规格。

### 5.5 清空首次安装状态

以下命令会清空应用数据库、授权记录和观看历史，只用于本地测试：

```bash
adb -s "$TV2000_DEVICE" shell pm clear \
  "$TV2000_PACKAGE"
```

随后重新启动，验证首次授权和频道编号。

### 5.6 模拟前后台

进入 Home：

```bash
adb -s "$TV2000_DEVICE" shell input keyevent 3
```

等待几秒后重新打开应用，验证：

- Home 时停止声音；
- 离开前完成保存；
- 返回后恢复原频道；
- 原本暂停时不得自动变成播放。

---

## 6. 在真实电视或盒子上测试

### 6.1 开启调试

电视厂商菜单名称可能不同，通常步骤为：

1. 打开系统设置；
2. 进入“关于”；
3. 连续点击 Build / 版本号 7 次；
4. 进入开发者选项；
5. 开启 USB debugging 或 Wireless debugging；
6. 电脑与电视连接同一可信局域网；
7. 在 Android Studio 使用 `Pair Devices Using Wi-Fi`，或使用厂商支持的 USB 调试连接；
8. 在电视上确认调试授权。

确认连接：

```bash
adb devices -l
```

指定当前真机：

```bash
export TV2000_DEVICE=<adb 显示的真机序列号>
export TV2000_PACKAGE=com.tv2000.app
```

### 6.2 安装与覆盖升级

首次安装：

```bash
adb -s "$TV2000_DEVICE" install \
  app/build/outputs/apk/debug/app-debug.apk
```

保留数据覆盖安装：

```bash
adb -s "$TV2000_DEVICE" install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

覆盖安装后必须确认：

- Room migration 成功；
- U 盘授权仍有效；
- 频道号不变；
- 观看历史保留；
- 上次频道可直接恢复。

### 6.3 真机每日冒烟

用标准 FAT32 或 exFAT 测试 U 盘执行：

1. 电视启动前插入 U 盘；
2. 启动 TV2000；
3. 首次运行选择整个 U 盘根目录；
4. 确认自动播放频道 1；
5. 按 `↓` 两次，再按 `↑` 一次；
6. 每个频道播放到不同位置；
7. Back 打开频道列表；
8. OK 选择频道；
9. Home 离开，再返回；
10. 拔出 U 盘；
11. 确认立即停止声音并显示“请插入原 U 盘或另一张影片盘”；
12. 插回同一 U 盘；
13. 确认自动恢复该盘原频道、节目和位置；
14. 退出并重新启动；
15. 确认仍恢复上次位置。

整个冒烟测试应控制在 15 分钟内。

### 6.4 物理插拔规则

模拟器广播只能验证状态机，以下行为必须用物理 U 盘：

- 播放中拔出；
- 扫描中拔出；
- prepare 中拔出；
- 同一个 U 盘插回；
- 另一个 U 盘插入；
- U 盘被系统以不同 URI 暴露；
- 两个卷使用相同卷标但不同文件系统 UUID；
- 同一 U 盘重新格式化后 UUID 改变；
- 机械硬盘休眠后重新唤醒；
- USB 供电不足；
- 电视待机期间 U 盘被移除。

人工插拔不得使用存有唯一数据的 U 盘。

单盘换碟至少执行以下矩阵：

| 场景 | 预期 |
| --- | --- |
| A 播放中拔出 | 捕获进度、立即静音停播并显示等待文案 |
| A 原盘插回 | 自动恢复 A 的最后频道、节目、位置和播放状态 |
| A 拔出后插入已认识的 B | 自动恢复 B 自己的历史，A/B 历史不串盘 |
| A 拔出后插入新盘 C | C 的频道号从数据库历史最大值继续增加 |
| 同名卷标 A/B 互换 | 按 UUID 分盘，频道和历史绝不合并 |
| A 重新格式化后插入 | UUID 改变，默认按新盘处理 |
| 当前盘缺失且同时出现多个盘 | 不任意选择，保持等待；选择列表留待后续 |

测试期间不得在 U 盘创建隐藏身份文件；应用对用户媒体保持只读。

---

## 7. 自动化测试命令

### 7.1 快速本地检查

每次提交前：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

### 7.2 模拟器仪器测试

保证只有目标模拟器在线，或通过 Gradle Managed Devices 固定设备，然后运行：

```bash
./gradlew connectedDebugAndroidTest
```

`connectedDebugAndroidTest` 会安装并清理测试 APK，部分 Android/Gradle 组合还会重装目标
Debug 应用。由于模拟器测试视频位于应用专用外部目录，重装可能同时删除
`/sdcard/Android/data/com.tv2000.app/files/TV2000-Test`。测试媒体的主副本必须保留在
开发电脑；仪器测试完成后如目录被清理，应重新执行第 5.2 节的 `adb push`。

如果工程拆分了存储、播放或数据库模块，应同时提供聚合任务，使开发者不需要逐模块猜测测试命令。

### 7.3 建议的模块测试任务

工程初始化时建议提供：

```text
checkMvp
    ├── testDebugUnitTest
    ├── lintDebug
    ├── connectedDebugAndroidTest
    └── verifyReleaseHasNoDebugStorageHooks
```

目标使用方式：

```bash
./gradlew checkMvp
```

`verifyReleaseHasNoDebugStorageHooks` 必须检查 Release APK 中不存在：

- DebugStorageProvider；
- debug 广播 action；
- 测试目录入口；
- 测试媒体；
- 可导出的测试 Receiver。

### 7.4 必须覆盖的单元测试

#### 自然排序

```text
1, 2, 10
01, 02, 10
S01E01, S01E02, S01E10
第1集, 第2集, 第10集
大小写
全角数字
超长数字
相同名称不同扩展名
子目录路径
```

#### 启动选择

```text
上次频道在线
上次频道离线
上次 Episode 存在
上次 Episode 删除
无历史
无 U 盘
有 U 盘但无视频
索引存在但文件失效
```

#### 播放状态机

```text
播放 → 暂停 → 播放
播放 → 切台
播放 → Home
播放 → U 盘移除
播放 → ENDED → 下一集
最后一集 → 停止
最后一集 → 循环
快速连续切台
prepare 中取消
播放错误后跳过
所有文件错误
```

#### 历史

```text
三个频道位置互不覆盖
小于 5 秒恢复到 0
正常保存最大间隔 5 秒
完成阈值
进程异常终止
Episode 时长变短
Room migration
```

---

## 8. 日志与问题定位

### 8.1 查看应用日志

清理旧日志：

```bash
adb -s "$TV2000_DEVICE" logcat -c
```

查看 TV2000、播放器和崩溃相关日志：

```bash
adb -s "$TV2000_DEVICE" logcat -v threadtime \
  'TV2000:D' \
  'AndroidRuntime:E' \
  '*:S'
```

如果播放器使用多个 Media3 tag，建议应用统一把关键播放器事件转换为 `TV2000` 结构化日志，而不是要求 QA 记住所有内部 tag。

### 8.2 每次缺陷需要的信息

- App versionName / versionCode；
- Git commit；
- 设备型号；
- Android 版本；
- U 盘型号、容量和文件系统；
- 媒体 SHA-256；
- 重现步骤；
- 期望结果；
- 实际结果；
- 发生时间；
- TV2000 日志；
- 是否可稳定重现；
- 视频或照片证据。

### 8.3 获取完整 bugreport

仅在需要系统级诊断时：

```bash
adb -s "$TV2000_DEVICE" bugreport \
  tv2000-bugreport.zip
```

bugreport 可能包含设备和用户环境信息，分享前必须脱敏。

### 8.4 检查内存

```bash
adb -s "$TV2000_DEVICE" shell dumpsys meminfo \
  "$TV2000_PACKAGE"
```

分别记录：

- Java Heap；
- Native Heap；
- Graphics；
- Code；
- TOTAL PSS。

不要只看 Java Heap；硬件播放器、Surface 和 native buffer 可能占用主要内存。

---

## 9. 性能的本地测法

### 9.1 启动到首帧

不能只使用 Activity 启动完成时间代替视频首帧。应用必须记录：

```text
app_launch_started_elapsed_ms
app_content_first_frame_elapsed_ms
playback_dependencies_ready_elapsed_ms
player_first_frame_elapsed_ms
```

单次耗时：

```text
first_frame - app_launch_started
```

`app_content_first_frame_elapsed_ms` 用于确认点击图标后 App 自身的静态启动画面何时可见；
`playback_dependencies_ready_elapsed_ms` 用于区分首屏显示和播放器依赖初始化耗时。两者只用于
拆分启动瓶颈，最终验收仍以视频 `player_first_frame_elapsed_ms` 为准。

每台真机执行 30 次：

1. U 盘已挂载；
2. 权限已授权；
3. 上次索引有效；
4. 强制停止应用；
5. 启动；
6. 等待首帧事件；
7. 收集本地结构化日志；
8. 报告 P50、P95、最大值。

验收值：P95 ≤2 秒。

### 9.2 切台

记录：

```text
channel_tune_requested_elapsed_ms
channel_first_frame_elapsed_ms
```

每台真机至少切台 100 次：

- 正向 50 次；
- 反向 50 次；
- 包含快速连续按键；
- 检查是否有声音重叠。

验收值：P95 ≤500ms。

### 9.3 扫描

使用 1、100、1000 文件目录分别测试：

- 第一次冷扫描；
- 索引存在的热扫描；
- 增加 1 个文件；
- 删除当前文件；
- 扫描中拔出。

另在模拟器用分阶段快照验证 MediaStore 渐进索引：先提交 `1 个频道 / 31 集`，再提交
`6 个频道`，最后提交 `16 个频道 / 每频道 84 集`。列表必须只增长且频道号保持稳定；完整索引
建立后再次收到较小临时快照，不得隐藏任何已有频道或 Episode。只有模拟“媒体扫描完成”或手动
重建索引的权威快照，才允许把缺失项目标记为离线。

记录：

```text
scan_started
first_playable_channel_found
scan_snapshot_committed
```

即使 1000 文件完整扫描未达到 1 秒，缓存命中的启动播放也不得等待完整扫描。

慢盘可靠性测试应分别记录系统 MediaStore 索引和 TV2000 查询。自动挂载路径不得出现 TV2000
对整盘文件树的二次遍历；MediaStore generation/version 未变化时不得重复枚举或写 Room。
如果电视在系统媒体扫描期间仍发生整机黑屏、USB 重置或掉盘，应继续检查供电、电缆、文件系统和
固件存储驱动；模拟器只能验证渐进合并、退避和 I/O 路径选择，不能证明物理盘稳定。

### 9.4 长时间测试

在真机使用 Release 构建：

- 连续播放 24 小时；
- 每 30 分钟记录一次 PSS；
- 每小时自动切台一次；
- 中途至少执行一次 Home/返回；
- 结束后验证历史；
- 检查是否存在 ANR、崩溃、音画停滞或持续内存增长。

---

## 10. 本地每日检查表

开发者提交前：

- [ ] `testDebugUnitTest` 通过
- [ ] `lintDebug` 通过
- [ ] `assembleDebug` 通过
- [ ] 模拟器能启动并自动播放
- [ ] 上下切台正确
- [ ] 左右 seek 正确
- [ ] OK 暂停/继续正确
- [ ] Back 频道列表/退出正确
- [ ] 三个频道历史独立
- [ ] 自动下一集正确
- [ ] 无 U 盘状态正确
- [ ] Debug 移除/插入恢复正确
- [ ] 没有新增文件选择入口

每日真机冒烟：

- [ ] FAT32 或 exFAT U 盘被识别
- [ ] 首帧正常
- [ ] 遥控器 KeyEvent 正常
- [ ] 物理拔出后声音停止
- [ ] 等待文案为“请插入原 U 盘或另一张影片盘”
- [ ] 原盘插回后自动恢复自己的历史
- [ ] 换入唯一另一盘后自动恢复另一盘自己的历史
- [ ] 新盘频道号接续数据库历史最大值
- [ ] 同名卷标不合并，格式化后按新盘处理
- [ ] Home 后没有后台声音
- [ ] 日志无崩溃和数据库异常

---

## 11. 推荐的第一天本地验证顺序

Android 工程初始化完成后，按以下顺序建立测试闭环：

1. 先写自然排序单元测试；
2. 建立 Fake StorageProvider；
3. 在 `TV2000_API_28` 推送三个频道测试数据；
4. 让 Debug 构建识别 `/Download/TV2000-Test`；
5. 播放一个 H.264 MP4；
6. 用 ADB keyevent 完成上下切台；
7. 重启应用验证历史；
8. 用 debug broadcast 模拟移除和恢复；
9. 安装到第一台真机；
10. 使用真实 U 盘重复同一流程。

完成这十步后，后续功能才能持续使用相同数据和命令进行回归。

---

## 12. 官方参考

- [Run apps on the Android Emulator](https://developer.android.com/studio/run/emulator)：创建和运行 Android TV AVD。
- [Run apps on a hardware device](https://developer.android.com/studio/run/device.html)：USB/Wi-Fi 调试和真机部署。
- [Android Debug Bridge](https://developer.android.com/tools/adb)：安装 APK、传输文件、执行设备命令和收集日志。
- [Access app-specific files](https://developer.android.com/training/data-storage/app-specific)：外部存储状态和虚拟外部卷测试。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：目录授权与持久访问。
