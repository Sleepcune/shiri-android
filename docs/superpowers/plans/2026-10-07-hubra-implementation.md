# Hubra 实现计划

目标：交付本地优先的安卓记账、课表、待办、统一 AI、组件、提醒、饮食与加密备份。

架构：纯 Kotlin core 保存可测试的领域模型和操作执行器；Android app 负责 Room 持久化、Compose 页面、Keystore、OCR、网络、提醒与 Glance。全部写入由同一操作接口执行，事务提交后更新组件与调度。

- [x] 项目与构建：固定 Gradle / Kotlin / Android 依赖，项目内安装缺失工具。
- [x] 领域模型与测试：金额、默认账户、转账、退款、核账、撤销、去重、周次、课程例外、重复任务、多学期隔离。
- [x] 本地服务：Room 事务保存、DataStore 设置、附件、加密备份及恢复流程。
- [x] AI：多配置、密钥保护、兼容接口与能力检测、结构化操作、OCR 与餐食照片。
- [x] 界面：五个页面、手动增改、首次设置、导入与核账、设置、日报、新建和切换学期。
- [x] 系统集成：六个组件、提醒、重启恢复、日报与快照后台任务。
- [x] 自动验证：90 项 JVM 测试、设备测试包编译、静态检查、签名 APK 和包结构验证。
- [ ] 设备与服务验收：连接真我 GT8、填写用户 API Key、运行设备测试及连续七日核账。当前缺设备/密钥/实际时间跨度，记录于 docs/VERIFICATION.md。

验证命令：`gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease`。
未连接真机及未提供 API Key 的项目必须标注未验证，不能以静态测试替代。
