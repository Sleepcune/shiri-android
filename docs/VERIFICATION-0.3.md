# 拾日 0.3.0 验证记录

日期：2026-10-11（Asia/Shanghai）

## 自动验证

- `:core:test`：90 项测试，0 失败。
- `:app:testDebugUnitTest`：77 项测试，0 失败。
- `:app:assembleDebugAndroidTest`：成功生成设备测试 APK。
- `:app:lintDebug`：成功，0 个 Error，49 个既有/非阻断 Warning（主要为 UseKtx、组件 XML 兼容属性和共享偏好 API 提示）。
- `:app:assembleRelease`：成功。
- 发布包：`deliverables/Shiri-0.3.0-release.apk`，`com.hubra.app`，versionCode 3，versionName 0.3.0，应用标签“拾日”。
- APK 签名：v2 验证通过；沿用项目现有发布签名配置。

## 尚需真机记录

当前环境没有连接真我 GT8，锁屏、息屏、后台进程回收、重启、电池优化和精确闹钟的最终投递仍需在手机上执行。安卓系统强制停止、关机和勿扰模式不作可绕过保证。

建议按顺序验证：通知权限与提醒频道、课前提醒、待办通知完成、22:00 核账、重启后恢复排程、0.2 覆盖安装后账户／课表／待办／图片／API 配置保留。

## 文件校验

- release APK SHA-256：`67330C83F2F9579D02C77651116E261515BD52809D74B236337E740AD56F95AF`
- source ZIP SHA-256：见 `deliverables/Shiri-0.3.0-SHA256SUMS.txt`


