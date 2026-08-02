# TV2000

Turn digital videos back into television.

TV2000 是一个面向 Android TV 的视频频道播放器：U 盘视频目录或 SMB 资源的一级目录会自动成为频道，应用启动后直接播放，遥控器上下键负责切台。

## 当前开发状态

当前垂直切片已经包含：

- Android TV / Google TV 入口；
- SAF 目录授权；
- 一级目录频道扫描；
- Episode 自然排序；
- Media3 本地播放；
- 遥控器切台、seek、双击换集、暂停；
- Room 媒体索引与稳定频道编号；
- 每频道播放位置保存与旧 DataStore 历史兼容；
- 本地媒体索引优先启动、后台刷新；
- 菜单键设置：切换资源、修改 U 盘视频目录、清除索引、重置进度与 App 数据；
- 单一活动资源：U 盘或一个 SMB2/SMB3 资源；
- SMB 目录扫描、流式播放、seek 和断点续播。

## U 盘目录

默认优先扫描 U 盘内的 `TV2000` 目录；如果该目录不存在，则回退扫描 U 盘根目录。可在“资源管理 → U盘 → 修改”中填写其他相对目录，留空表示始终使用根目录。

## SMB 资源

按遥控器菜单键，依次选择“选择资源”→“增加 SMB 资源”。地址格式：

```text
smb://192.168.1.10/共享名/可选目录
```

账号、密码和域可以留空。保存后会立即选择该 SMB 资源；切回 U 盘时不会删除 SMB 配置、媒体索引或各频道进度。当前版本不支持 SMB1，也不支持同时合并 U 盘和 SMB 频道。

## 本地构建

工程要求：

- Android Studio 当前稳定版；
- Android SDK Platform 37；
- Android SDK Build Tools 36.0.0；
- Android Studio 自带 JDK；
- Android TV 9 / API 28+ 运行设备。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
./gradlew assembleDebug
```

详细资料：

- [产品与技术规格](./docs/TV2000-SPEC.md)
- [MVP 开发与测试计划](./docs/TV2000-MVP-DEVELOPMENT-TEST-PLAN.md)
- [本地测试指南](./docs/TV2000-LOCAL-TESTING.md)
