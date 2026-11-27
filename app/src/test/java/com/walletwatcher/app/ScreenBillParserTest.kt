package com.walletwatcher.app

import com.walletwatcher.app.core.AccessibilitySnapshotCollector
import com.walletwatcher.app.core.AccessibleNode
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.core.ScreenCaptureDeduplicator
import com.walletwatcher.app.core.ScreenPageKind
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ScreenBillParserTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private class FakeNode(
        override val packageName: String? = ScreenBillParser.PKG_WECHAT,
        override val windowId: Int = 10,
        override val isPassword: Boolean = false,
        override val text: String? = null,
        override val contentDescription: String? = null,
        private val children: List<FakeNode> = emptyList(),
        private val onReadChild: (() -> Unit)? = null
    ) : AccessibleNode {
        var childReads: Int = 0
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): AccessibleNode {
            childReads++
            onReadChild?.invoke()
            return children[index]
        }
    }

    @Test
    fun rejectsNonWhitelistedPackageChatWindowAndPasswordScreens() {
        assertNull(
            ScreenBillParser.parse(
                packageName = "com.example.other",
                className = "PaySuccessActivity",
                rawTexts = listOf("支付成功", "瑞幸咖啡", "¥19.90")
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "WalletPayUI",
                rawTexts = listOf("支付成功", "瑞幸咖啡", "¥19.90"),
                hasPasswordField = true
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "com.tencent.mm.ui.LauncherUI",
                rawTexts = listOf("支付成功", "瑞幸咖啡", "¥19.90")
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "com.tencent.mm.ui.chatting.ChattingUI",
                rawTexts = listOf("你已收款，资金已存入零钱", "¥3.30")
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_ALIPAY,
                className = "com.alipay.android.msp.ui.PayPwdActivity",
                rawTexts = listOf("支付成功", "¥19.90")
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = null,
                rawTexts = listOf("请输入支付密码", "瑞幸咖啡", "¥19.90", "安全键盘")
            )
        )
        assertNull(
            ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = null,
                rawTexts = listOf("好友张三", "支付成功 ¥88.00", "按住说话", "更多功能按钮")
            )
        )
    }

    @Test
    fun snapshotCollectorRejectsSwitchedRootPackageAndWindowIdMismatchBeforeReadingNodes() {
        // Case 1: Event came from WeChat, but active window already switched to Chrome
        val chromeRoot = FakeNode(
            packageName = "com.android.chrome",
            windowId = 10,
            children = listOf(FakeNode(packageName = "com.android.chrome", text = "支付成功 ¥19.90"))
        )
        val res1 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = chromeRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertFalse(res1.isSafeToParse)
        assertEquals("root_package_mismatch", res1.rejectedReason)
        assertTrue(res1.texts.isEmpty())
        assertEquals(0, chromeRoot.childReads)

        // Case 2: Same package, different windowId (split screen / switched window)
        val otherWindowRoot = FakeNode(
            packageName = ScreenBillParser.PKG_WECHAT,
            windowId = 22,
            children = listOf(FakeNode(text = "支付成功 ¥19.90"))
        )
        val res2 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = otherWindowRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertFalse(res2.isSafeToParse)
        assertEquals("window_id_mismatch", res2.rejectedReason)
        assertTrue(res2.texts.isEmpty())
        assertEquals(0, otherWindowRoot.childReads)
    }

    @Test
    fun snapshotCollectorDiscardsTruncatedTreeWhenPasswordBeyondLimitOrSlowNodeExceedsBudget() {
        // Case 1: Password node sits BEYOND maxNodes limit -> truncated snapshot MUST be discarded!
        val children = mutableListOf(
            FakeNode(text = "支付成功"),
            FakeNode(text = "瑞幸咖啡"),
            FakeNode(text = "¥19.90")
        )
        repeat(10) { children.add(FakeNode(text = "杂项$it")) }
        children.add(FakeNode(isPassword = true, text = "••••••"))

        val root1 = FakeNode(children = children)
        val res1 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = root1,
            windowClassForRoot = "WalletOrderInfoNewUI",
            maxNodes = 6
        )
        assertTrue(res1.truncated)
        assertFalse(res1.isSafeToParse)
        assertTrue(res1.texts.isEmpty())

        // Case 2: Slow node query exceeds 50ms budget -> MUST discard partial snapshot!
        var fakeClock = 1_000_000L
        val slowRoot = FakeNode(
            children = listOf(
                FakeNode(text = "支付成功"),
                FakeNode(text = "¥19.90")
            ),
            onReadChild = { fakeClock += 30_000_000L } // 30ms per child -> exceeds 50ms on 2nd child
        )
        val res2 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = slowRoot,
            windowClassForRoot = "WalletOrderInfoNewUI",
            nanoTimeProvider = { fakeClock },
            budgetNanos = 50_000_000L
        )
        assertTrue(res2.truncated)
        assertFalse(res2.isSafeToParse)
        assertTrue(res2.texts.isEmpty())

        // Case 3: Password field within budget -> immediately rejected
        val pwdRoot = FakeNode(
            children = listOf(FakeNode(text = "支付成功"), FakeNode(isPassword = true))
        )
        val res3 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = pwdRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertTrue(res3.hasPasswordField)
        assertFalse(res3.isSafeToParse)
        assertTrue(res3.texts.isEmpty())

        // Case 4: Realistic WeChat payment page with 40 nesting levels and 900 sibling/banner nodes (as seen with flagIncludeNotImportantViews) -> accepted and records visitedNodes
        var deepLeaf = FakeNode(
            children = listOf(FakeNode(text = "支付成功"), FakeNode(text = "瑞幸咖啡"), FakeNode(text = "¥19.90"))
        )
        repeat(38) { deepLeaf = FakeNode(children = listOf(deepLeaf)) }
        val bannerSiblings = List(850) { FakeNode(text = "") }
        val realisticRoot = FakeNode(children = listOf(deepLeaf) + bannerSiblings)
        val res4 = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = realisticRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertTrue(res4.isSafeToParse)
        assertFalse(res4.truncated)
        assertTrue(res4.visitedNodes >= 890)
        assertTrue("支付成功" in res4.texts)
    }

    @Test
    fun parsesWeChatPaySuccessIncludingSplitCurrencySymbolAndIgnoresDiscount() {
        val cap = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
            rawTexts = listOf(
                "支付成功",
                "星巴克(南山店)",
                "¥",
                "32.50",
                "优惠",
                "¥5.00",
                "付款方式",
                "招商银行储蓄卡(6288)",
                "完成"
            ),
            observedAtMs = 1_700_000_000_000L
        )
        assertNotNull(cap)
        assertEquals(ScreenPageKind.PAY_SUCCESS, cap!!.pageKind)
        assertEquals("OUT", cap.directionHint)
        assertEquals(3250L, cap.amountMinor)
        assertEquals("星巴克(南山店)", cap.merchant)
        assertEquals("招商银行储蓄卡(6288)", cap.paymentMethod)
        assertFalse(cap.hasAmbiguousAmounts)
    }

    @Test
    fun livePaymentSuccessWithPaymentTimeAndMethodNotMisclassifiedAsBillDetail() {
        val cap = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
            rawTexts = listOf(
                "支付成功",
                "蜜雪冰城",
                "¥12.00",
                "支付方式",
                "零钱",
                "支付时间",
                "2026-11-26 15:00:00",
                "完成"
            ),
            zoneId = shanghai
        )
        assertNotNull(cap)
        assertEquals(ScreenPageKind.PAY_SUCCESS, cap!!.pageKind)
        assertEquals(1200L, cap.amountMinor)
        assertEquals("蜜雪冰城", cap.merchant)
    }

    @Test
    fun transferSuccessPreservesVerbInternalHintAndFlagsAmbiguousMultiAmount() {
        val transfer = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceResultUI",
            rawTexts = listOf("转账成功", "零钱充值", "¥500.00", "完成")
        )
        assertNotNull(transfer)
        assertEquals("转账成功", transfer!!.actionVerb)
        assertEquals("零钱充值", transfer.internalHint)
        assertTrue(transfer.toRawNotification().content.contains("零钱充值"))

        // Two distinct non-discounted amounts without "实付" -> must flag ambiguous!
        val ambiguous = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "MspContainerActivity",
            rawTexts = listOf("支付成功", "某餐厅", "¥88.00", "¥68.00", "完成")
        )
        assertNotNull(ambiguous)
        assertTrue(ambiguous!!.hasAmbiguousAmounts)
        assertNotNull(ambiguous.toRawNotification().ambiguityOverride)
    }

    @Test
    fun parsesWeChatGroupReceiveSuccessWithoutSyntheticExternalIdOrLeakingRawNote() {
        val transfer = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            rawTexts = listOf(
                "你已收款，资金已存入零钱",
                "¥3.30",
                "查看零钱",
                "转账说明",
                "给李四的午饭AA返还 电话13912345678 科技园A座",
                "转账时间",
                "2026-11-26 12:30:15",
                "收款时间",
                "2026-11-26 12:31:02"
            ),
            observedAtMs = 1_700_000_000_000L,
            zoneId = shanghai
        )
        assertNotNull(transfer)
        assertEquals(ScreenPageKind.RECEIVE_SUCCESS, transfer!!.pageKind)
        assertEquals("IN", transfer.directionHint)
        assertEquals(330L, transfer.amountMinor)
        assertEquals("AA返还", transfer.structuredNoteTag)
        assertEquals("微信收款", transfer.merchant)
        assertNotNull(transfer.eventTimeMs)
        // Without a real platform order number, externalId MUST stay null, while dual-second receiptDedupKey is populated
        assertNull(transfer.externalId)
        assertNotNull(transfer.receiptDedupKey)

        // Minute-only timestamps MUST NOT create a receiptDedupKey (avoid minute-precision collisions)
        val minuteOnly = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            rawTexts = listOf(
                "你已收款，资金已存入零钱", "¥3.30",
                "转账时间", "2026-11-26 12:30",
                "收款时间", "2026-11-26 12:31"
            ),
            zoneId = shanghai
        )!!
        assertNull(minuteOnly.receiptDedupKey)

        val notifContent = transfer.toRawNotification().content
        assertFalse("Free-text third-party info must not leak", notifContent.contains("李四"))
        assertFalse("Free-text phone must not leak", notifContent.contains("13912345678"))
        assertFalse("Free-text address must not leak", notifContent.contains("科技园"))
        assertTrue(notifContent.contains("AA返还"))

        val redPacket = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
            rawTexts = listOf(
                "王五的红包",
                "恭喜发财，大吉大利",
                "8.88",
                "元",
                "已存入零钱，可直接消费"
            ),
            observedAtMs = 1_700_000_000_000L
        )
        assertNotNull(redPacket)
        assertEquals(ScreenPageKind.RECEIVE_SUCCESS, redPacket!!.pageKind)
        assertEquals(888L, redPacket.amountMinor)
        assertEquals("王五", redPacket.merchant)
    }

    @Test
    fun parsesWeChatAndAlipayBillDetailPagesWithHashedExternalId() {
        val wxDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.mall.ui.MallTransactionDetailUI",
            rawTexts = listOf(
                "返回",
                "扫二维码付款-给张记牛肉面",
                "-26.00",
                "当前状态",
                "支付成功",
                "支付时间",
                "2026年11月26日 12:08:30",
                "支付方式",
                "零钱",
                "交易单号",
                "42000024001122334455"
            ),
            zoneId = shanghai
        )
        assertNotNull(wxDetail)
        assertEquals(ScreenPageKind.BILL_DETAIL, wxDetail!!.pageKind)
        assertEquals("OUT", wxDetail.directionHint)
        assertEquals(2600L, wxDetail.amountMinor)
        assertEquals("张记牛肉面", wxDetail.merchant)
        assertEquals("零钱", wxDetail.paymentMethod)
        assertNotNull(wxDetail.externalId)
        assertFalse("Raw order number must be hashed", wxDetail.externalId!!.contains("4200002400"))

        val aliDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "com.alipay.mobile.bill.detail.BillDetailActivity",
            rawTexts = listOf(
                "账单详情",
                "盒马鲜生",
                "-68.40",
                "交易成功",
                "付款方式",
                "花呗",
                "商品说明",
                "生鲜采购",
                "创建时间",
                "2026-11-26 19:02:11",
                "订单号",
                "202611262200141234567890"
            ),
            zoneId = shanghai
        )
        assertNotNull(aliDetail)
        assertEquals(ScreenPageKind.BILL_DETAIL, aliDetail!!.pageKind)
        assertEquals("OUT", aliDetail.directionHint)
        assertEquals(6840L, aliDetail.amountMinor)
        assertEquals("盒马鲜生", aliDetail.merchant)
        assertEquals("花呗", aliDetail.paymentMethod)
        assertNotNull(aliDetail.externalId)
    }

    @Test
    fun parsesAlipayPaySuccessPreferringActualPaidOverListPriceAndDiscount() {
        val cap = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "com.alipay.android.msp.ui.views.MspContainerActivity",
            rawTexts = listOf(
                "支付成功",
                "原价",
                "¥5.00",
                "优惠",
                "¥1.70",
                "实付",
                "¥3.30",
                "收款方",
                "蜜雪冰城",
                "完成"
            )
        )
        assertNotNull(cap)
        assertEquals(ScreenPageKind.PAY_SUCCESS, cap!!.pageKind)
        assertEquals(330L, cap.amountMinor)
        assertEquals("蜜雪冰城", cap.merchant)
        assertFalse(cap.hasAmbiguousAmounts)
    }

    @Test
    fun windowSessionDeduplicatorSuppressesLongStayRedrawsAllowsSecondPurchaseAndReleasesOnFailure() {
        val dedup = ScreenCaptureDeduplicator(sameSessionJitterMs = 1_500L)
        val t0 = 1_700_000_000_000L
        val first = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf("支付成功", "瑞幸咖啡", "¥3.30", "完成"),
            observedAtMs = t0
        )!!

        // 1. Accepted in session 1
        assertTrue(dedup.tryAcquire(windowSessionId = 1L, capture = first, nowMs = t0))
        // 2. Staying on SAME result page even > 60 seconds later with UI redraw -> STILL suppressed!
        assertFalse(dedup.tryAcquire(windowSessionId = 1L, capture = first.copy(observedAtMs = t0 + 70_000L), nowMs = t0 + 70_000L))
        // 3. User exits and makes a SECOND real ¥3.30 purchase at same merchant 10s later (session 3) -> ACCEPTED!
        assertTrue(dedup.tryAcquire(windowSessionId = 3L, capture = first.copy(observedAtMs = t0 + 10_000L), nowMs = t0 + 10_000L))
        // 4. If async write fails and releases session 3, retry in session 3 succeeds!
        dedup.releaseOnFailure(windowSessionId = 3L, capture = first)
        assertTrue(dedup.tryAcquire(windowSessionId = 3L, capture = first.copy(observedAtMs = t0 + 12_000L), nowMs = t0 + 12_000L))
    }

    @Test
    fun deduplicatorAcceptsDistinctExternalIdsWithIdenticalFingerprintInSameSession() {
        val dedup = ScreenCaptureDeduplicator(sameSessionJitterMs = 1_500L)
        val t0 = 1_700_000_000_000L
        val orderA = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "瑞幸咖啡", "-19.90",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 12:00:00",
                "交易单号", "42000011110000000001"
            ),
            observedAtMs = t0,
            zoneId = shanghai
        )!!
        val orderB = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "瑞幸咖啡", "-19.90",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 12:00:00",
                "交易单号", "42000011110000000002"
            ),
            observedAtMs = t0 + 200L,
            zoneId = shanghai
        )!!
        assertEquals(orderA.screenFingerprint, orderB.screenFingerprint)
        assertNotEquals(orderA.externalId, orderB.externalId)

        // Same window session, identical content fingerprint, distinct genuine order IDs -> BOTH accepted!
        assertTrue(dedup.tryAcquire(windowSessionId = 1L, capture = orderA, nowMs = t0))
        assertTrue(dedup.tryAcquire(windowSessionId = 1L, capture = orderB, nowMs = t0 + 200L))
        // Redelivering either A or B is rejected
        assertFalse(dedup.tryAcquire(windowSessionId = 1L, capture = orderA, nowMs = t0 + 500L))
        assertFalse(dedup.tryAcquire(windowSessionId = 1L, capture = orderB, nowMs = t0 + 600L))
        assertFalse(dedup.tryAcquire(windowSessionId = 2L, capture = orderB, nowMs = t0 + 700L))
        // Failure rollback on B only frees B, keeping A suppressed
        dedup.releaseOnFailure(windowSessionId = 1L, capture = orderB)
        assertFalse(dedup.tryAcquire(windowSessionId = 1L, capture = orderA, nowMs = t0 + 800L))
        assertTrue(dedup.tryAcquire(windowSessionId = 1L, capture = orderB, nowMs = t0 + 900L))
    }

    @Test
    fun snapshotCollectorRejectsThrowingChildButAllowsNormalNullChildSlot() {
        // 1. Readable payment nodes + a sibling throwing exception on getChild() -> MUST reject entire tree!
        val throwingRoot = object : AccessibleNode {
            override val packageName = ScreenBillParser.PKG_WECHAT
            override val windowId = 10
            override val isPassword = false
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount = 4
            override fun getChild(index: Int): AccessibleNode? = when (index) {
                0 -> FakeNode(text = "支付成功")
                1 -> FakeNode(text = "瑞幸咖啡")
                2 -> FakeNode(text = "¥19.90")
                else -> throw IllegalStateException("IPC child read failed")
            }
        }
        val throwRes = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = throwingRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertFalse(throwRes.isSafeToParse)
        assertTrue(throwRes.truncated)
        assertEquals("child_read_error", throwRes.rejectedReason)
        assertTrue(throwRes.texts.isEmpty())

        // 2. Readable payment nodes + a normal null child slot (RecyclerView / non-important view) -> STILL accepted!
        val nullSlotRoot = object : AccessibleNode {
            override val packageName = ScreenBillParser.PKG_WECHAT
            override val windowId = 10
            override val isPassword = false
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount = 4
            override fun getChild(index: Int): AccessibleNode? = when (index) {
                0 -> FakeNode(text = "支付成功")
                1 -> FakeNode(text = "瑞幸咖啡")
                2 -> FakeNode(text = "¥19.90")
                else -> null
            }
        }
        val nullSlotRes = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = nullSlotRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertTrue(nullSlotRes.isSafeToParse)
        assertFalse(nullSlotRes.truncated)
        assertEquals(listOf("支付成功", "瑞幸咖啡", "¥19.90"), nullSlotRes.texts)

        // 3. Valid eventWindowId (10) vs unknown root windowId (-1) -> rejected as window_id_mismatch
        val unknownWinRoot = FakeNode(windowId = -1, children = listOf(FakeNode(text = "支付成功"), FakeNode(text = "¥19.90")))
        val mismatchRes = AccessibilitySnapshotCollector.collect(
            eventPackage = ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            root = unknownWinRoot,
            windowClassForRoot = "WalletOrderInfoNewUI"
        )
        assertFalse(mismatchRes.isSafeToParse)
        assertEquals("window_id_mismatch", mismatchRes.rejectedReason)
    }

    @Test
    fun productDescriptionAndRemarksAdjacentToAmountNeverLeakIntoMerchant() {
        // 1. Bill detail where "商品说明" + private text sits directly before "-3.30", while explicit "商户全称" follows
        val sentinel1 = "PRIVATE_ITEM_SENTINEL_8472"
        val detailWithExplicitMerchant = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "BillDetailActivity",
            rawTexts = listOf(
                "账单详情",
                "商品说明",
                sentinel1,
                "-3.30",
                "交易状态",
                "交易成功",
                "商户全称",
                "瑞幸咖啡",
                "创建时间",
                "2026-11-26 12:00:00"
            ),
            zoneId = shanghai
        )!!
        assertEquals("瑞幸咖啡", detailWithExplicitMerchant.merchant)
        assertEquals(sentinel1, detailWithExplicitMerchant.itemDescription)
        assertFalse(detailWithExplicitMerchant.toRawNotification().content.contains(sentinel1))

        // 2. Inline "商品说明：sentinel" right before amount with NO explicit merchant -> merchant stays "未知商户"
        val sentinel2 = "PRIVATE_INLINE_9913"
        val detailNoMerchant = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "BillDetailActivity",
            rawTexts = listOf(
                "账单详情",
                "商品说明：$sentinel2",
                "-3.30",
                "交易状态",
                "交易成功",
                "创建时间",
                "2026-11-26 12:00:00"
            ),
            zoneId = shanghai
        )!!
        assertEquals("未知商户", detailNoMerchant.merchant)
        assertEquals(sentinel2, detailNoMerchant.itemDescription)
        assertFalse(detailNoMerchant.toRawNotification().content.contains(sentinel2))

        // 3. Transfer remark right before amount -> never promoted to merchant or cloud content
        val sentinel3 = "PRIVATE_REMARK_7741"
        val remarkDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "账单详情",
                "转账说明",
                sentinel3,
                "-3.30",
                "当前状态",
                "支付成功",
                "支付时间",
                "2026-11-26 12:00:00"
            ),
            zoneId = shanghai
        )!!
        assertEquals("未知商户", remarkDetail.merchant)
        assertFalse(remarkDetail.toRawNotification().content.contains(sentinel3))

        // 4. Multi-node free-text product/remark split across multiple nodes (even with standalone date inside!) -> NONE leaks!
        val multiNodePay = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf("支付成功", "商品说明", "定制礼盒", "2026-11-26 10:30:00", "送给小林", "¥19.90"),
            zoneId = shanghai
        )!!
        assertEquals("未知商户", multiNodePay.merchant)
        assertEquals("定制礼盒", multiNodePay.itemDescription)
        assertFalse(multiNodePay.toRawNotification().content.contains("定制礼盒"))
        assertFalse(multiNodePay.toRawNotification().content.contains("送给小林"))

        // 5. Bill detail with date ONLY inside "备注" (no explicit payment time label) -> eventTimeMs MUST remain null!
        val remarkDateOnly = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf("账单详情", "-3.30", "备注", "预约时间 2026-11-26 10:30:00"),
            zoneId = shanghai
        )!!
        assertEquals(ScreenPageKind.BILL_DETAIL, remarkDateOnly.pageKind)
        assertNull("Date inside 备注 must NEVER count as trusted BILL_DETAIL transaction time", remarkDateOnly.eventTimeMs)
    }

    @Test
    fun receiptDedupKeySeparatesDistinctKnownPayersAtIdenticalSeconds() {
        val payerA = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "RemittanceDetailUI",
            rawTexts = listOf(
                "你已收款，资金已存入零钱", "¥3.30",
                "付款方", "张三",
                "转账时间", "2026-11-26 12:30:15",
                "收款时间", "2026-11-26 12:31:02"
            ),
            zoneId = shanghai
        )!!
        val payerB = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "RemittanceDetailUI",
            rawTexts = listOf(
                "你已收款，资金已存入零钱", "¥3.30",
                "付款方", "李四",
                "转账时间", "2026-11-26 12:30:15",
                "收款时间", "2026-11-26 12:31:02"
            ),
            zoneId = shanghai
        )!!
        assertNotNull(payerA.receiptDedupKey)
        assertNotNull(payerB.receiptDedupKey)
        assertNotEquals(payerA.receiptDedupKey, payerB.receiptDedupKey)
    }
}
