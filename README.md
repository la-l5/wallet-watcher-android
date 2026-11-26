# Wallet Watcher (Android)

本地优先、免 Root、零无障碍权限的开源 Android 自动记账工具（Apache-2.0）。

## 核心设计原则

- **默认纯离线、零 API Key 开箱即用**：无遥测、不内置作者 API Key；关闭可选 AI 时零推理请求。
- **只记真实外部收支**：过滤钱包充提、余额宝/零钱通/理财申赎、信用账单还款、银证转账与唯一候选的本人双卡镜像互转；拆出提现/分期手续费为独立外部支出；区分商户退款与 AA/报销冲抵。
- **可解释、可拆分、可撤销**：四表多对多模型（`transactions`、`transaction_sources`、`transaction_source_links`、`transaction_changes`）；金额使用非负整数最小单位（如分）与币种精度存储；历史合并记录诚实标记缺少独立来源。
- **带角色金额候选与受约束 AI**：本地先抽取带角色候选（实付/本金/手续费/原价/优惠/余额/应还提醒/外币估值），可选 AI 仅建议候选编号与允许分类，不能越权写入余额或自造金额。
- **逻辑快照加密备份与原子恢复**：单 SQLite 读事务导出快照，使用 Android API 26+ 原生 `PBKDF2WithHmacSHA256` + `AES-256-GCM`（带头部 AAD 认证），默认排除 API Key；恢复先写入并校验临时库再原子切换，异常时原库保持完整。

## 构建与签名说明

- **Debug 构建**（`.github/workflows/build-apk.yml`）：使用 `com.walletwatcher.app.debug` 包名后缀，可与正式版共存，不接触正式签名私钥，且不注入任何个人 API Key。
- **Release 构建**（`.github/workflows/release-apk.yml`）：在受保护的 `release` Environment 中通过 Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）使用固定签名打包 `com.walletwatcher.app`，并同时输出 **APK 文件 SHA-256** 与 **APK 签名证书 SHA-256 指纹**。

## 验证命令

```bash
gradle testDebugUnitTest --stacktrace
gradle assembleDebug --no-build-cache --no-configuration-cache
```

## 开源协议

本项目采用 [Apache License 2.0](LICENSE)。
