# WalletWatcher Android (MVP 真机测试版)

极简原生 Android（Kotlin）自动记账应用，专为免 Root 手机（含荣耀 MagicOS / Android 14~15）设计。

## 核心能力

- **纯通知栏文字监听**：免 Root，仅申请 `NotificationListenerService` 通知使用权，支持微信折叠聚合通知展开。
- **10 秒跨渠道防重合并**：微信 + 银行卡 + 云闪付并发弹出自动合为一笔，合并扣款账户与商户信息。
- **只记外部↔内部收支**：三道过滤网拦截提现、充值、余额宝/零钱通、花呗/信用卡还款、银证转账（银行↔证券）、自己转自己（本人实名档案 + 3 分钟等额一出一进镜像对冲）。
- **先用后付闭环**：0 元占坑自动忽略，确认收货真实扣款再入账；信用消费当下记支出、月底还款自动忽略。
- **退款冲抵**：商户退款不虚增收入，自动扣减本月真实净支出。
- **离线容错 + 隐私脱敏**：发给云端前自动掩码银行卡号/手机号/身份证号/实名；断网自动暂存本地 SQLite，联网自动补发。
- **AI 接口预留**：默认运行内置模拟 AI 零成本测试，设置页随时切换任意 OpenAI 兼容大模型 API（DeepSeek / Qwen / GLM 等）。
- **荣耀真机保活适配**：Android 14/15 `specialUse` 前台低优先级常驻状态通知 + 一键跳转应用启动管理与电池白名单。

## 云端编译 APK（无需本机装 Android Studio）

1. 将本目录推送到你的 GitHub **Private（私有）仓库** 的 `main` 分支。
2. GitHub Actions 会自动运行 `.github/workflows/build-apk.yml`：先执行全部纯 JVM 单元测试，再生成 `app-debug.apk`。
3. 在仓库的 **Actions** -> 最新运行记录 -> **Artifacts** 中下载 `wallet-watcher-debug-apk` 解压，将 `.apk` 传到荣耀手机安装即可。
