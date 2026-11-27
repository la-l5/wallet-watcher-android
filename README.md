# Wallet Watcher (Android)

本地优先、免 Root、公开开源（Apache-2.0）的 Android 自动记账工具，以纯通知监听为默认底座，并提供默认关闭、自愿开启的只读屏幕增强。

## 核心设计原则

- **默认纯离线、零 API Key 开箱即用**：无遥测、不内置作者 API Key；关闭可选 AI 且未手动点「检查更新」时零网络请求。
- **双引擎隐私采集**：默认仅通过 `NotificationListenerService` 离线解析支付/银行通知；可选自愿开启只读 `WalletAccessibilityService`（系统级 XML 锁死 `com.tencent.mm` / `com.eg.android.AlipayGphone`，零模拟点击权限，仅抓支付成功/收款/账单详情固定锚点，拒绝聊天页与密码页）。
- **只记真实外部收支**：过滤钱包充提、余额宝/零钱通/理财申赎、信用账单还款、银证转账与唯一候选的本人双卡镜像互转；拆出提现/分期手续费为独立外部支出；区分商户退款与 AA/报销冲抵。
- **可解释、可拆分、可撤销**：四表多对多模型（`transactions`、`transaction_sources`、`transaction_source_links`、`transaction_changes`）；金额使用非负整数最小单位（如分）与币种精度存储；所有自动合并与屏幕回填均支持来源核查与安全撤销。
- **逻辑快照加密备份与原子恢复**：单 SQLite 读事务导出快照，使用 Android API 26+ 原生 `PBKDF2WithHmacSHA256` + `AES-256-GCM`（带头部 AAD 认证），默认排除 API Key；恢复先写入并校验临时库再原子切换，异常时原库保持完整。
- **手动 SHA-256 校验更新**：设置页提供手动「检查 GitHub 新版本」（不后台偷跑联网、不内置 GitHub Token），强制校验 GitHub HTTPS 白名单与 `checksums.txt` 中的 APK SHA-256、包名、版本号及签名证书一致性，验证通过后才拉起系统安装器保留数据覆盖更新。**注意**：客户端以匿名只读方式访问 `https://api.github.com/repos/la-l5/wallet-watcher-android/releases`，因此在仓库转为公开（Public）并发布包含 APK 与 `checksums.txt` 的正式 Release 之前，应用内「检查更新」会返回 HTTP 404 且不可用。

## 构建、签名与联网覆盖更新

- **Debug 构建**（`.github/workflows/build-apk.yml`）：使用 `com.walletwatcher.app.debug` 包名后缀，可与正式版共存，不接触正式签名私钥，且不注入任何个人 API Key。因 CI 虚拟机每次使用临时 debug 签名，Debug 包之间无法覆盖安装；在 `version.properties` 填入官方公开证书指纹 `RELEASE_CERT_SHA256` 之前，Debug 版应用内更新仅提供浏览器打开 GitHub Releases 入口，禁止未经发布者签名校验的首次共存安装。
- **Release 构建与自动发布**（`.github/workflows/release-apk.yml`）：版本号与可选的公开签名证书指纹统一由仓库根目录 `version.properties`（`VERSION_CODE`、`VERSION_NAME`、`RELEASE_CERT_SHA256`）受版本控制管理；推送 `v*` 标签前需同步递增 `VERSION_CODE` 与 `VERSION_NAME`（工作流使用固定仓库级并发锁，会在解码私钥前全量分页校验标签一致且 `VERSION_CODE` 严格高于历史已发布版本，构建后用 `aapt2` 与 `apksigner` 核对实际 APK 版本元数据与签名证书指纹，并执行 Release DEX 零密钥扫描），在受保护的 `release` Environment 中使用固定 Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）签名打包 `com.walletwatcher.app`，输出 `checksums.txt` 并自动发布 GitHub Release，仓库公开后可供 App 内「检查更新」与 Obtainium 直接保留数据覆盖升级。

## 验证命令

```bash
gradle testDebugUnitTest --stacktrace
gradle assembleDebug --no-build-cache --no-configuration-cache
```

## 开源协议

本项目采用 [Apache License 2.0](LICENSE)。
