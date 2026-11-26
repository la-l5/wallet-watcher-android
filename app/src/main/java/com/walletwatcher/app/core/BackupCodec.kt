package com.walletwatcher.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pure Kotlin/JVM authenticated encrypted container for logical ledger snapshots.
 * Uses Android API 26+ native PBKDF2WithHmacSHA256 + AES/GCM/NoPadding (zero external crypto deps).
 * Header metadata is bound as GCM AAD so tampering with KDF params, salt, nonce, or version fails auth.
 */
object BackupCodec {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val CONTAINER_VERSION = 1
    const val SNAPSHOT_SCHEMA_VERSION = 3
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    const val DEFAULT_ITERATIONS = 120_000
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 1_000_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256
    const val MAX_CONTAINER_BYTES = 16 * 1024 * 1024
    const val MAX_HEADER_BYTES = 8 * 1024
    const val MAX_TRANSACTIONS = 100_000

    private val secureRandom = SecureRandom()

    fun encryptSnapshot(
        snapshot: BackupSnapshot,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        if (password.isEmpty()) throw BackupException("备份密码不能为空")
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException("KDF 迭代参数超出安全范围")
        }

        val plaintext = serializeSnapshot(snapshot).toString().toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_CONTAINER_BYTES - MAX_HEADER_BYTES) {
            throw BackupException("备份数据体积超过安全上限（16MB）")
        }

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val headerObj = JSONObject().apply {
            put("formatVersion", CONTAINER_VERSION)
            put("schemaVersion", snapshot.schemaVersion)
            put("kdf", KDF_ALGORITHM)
            put("iterations", iterations)
            put("saltB64", Base64.getEncoder().encodeToString(salt))
            put("cipher", CIPHER_ALGORITHM)
            put("nonceB64", Base64.getEncoder().encodeToString(nonce))
            put("tagBits", TAG_BITS)
        }
        val headerBytes = headerObj.toString().toByteArray(Charsets.UTF_8)

        val keyBytes = deriveKey(password, salt, iterations)
        val ciphertext = try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(headerBytes)
            cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw BackupException("加密备份失败：${e.javaClass.simpleName}", e)
        } finally {
            keyBytes.fill(0)
        }

        val out = ByteArrayOutputStream(MAGIC.size + 4 + headerBytes.size + ciphertext.size)
        DataOutputStream(out).use { dos ->
            dos.write(MAGIC)
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            dos.write(ciphertext)
        }
        return out.toByteArray()
    }

    fun decryptSnapshot(containerBytes: ByteArray, password: CharArray): BackupSnapshot {
        if (password.isEmpty()) throw BackupException("恢复密码不能为空")
        if (containerBytes.isEmpty()) throw BackupException("备份文件为空")
        if (containerBytes.size > MAX_CONTAINER_BYTES) {
            throw BackupException("备份文件超过安全体积上限（16MB）")
        }
        if (containerBytes.size < MAGIC.size + 4 + 16) {
            throw BackupException("备份文件已截断或格式无效")
        }

        val dis = DataInputStream(ByteArrayInputStream(containerBytes))
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupException("不是有效的钱包监测加密备份文件")
        }

        val headerLen = dis.readInt()
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES || headerLen > containerBytes.size - 8) {
            throw BackupException("备份文件头长度异常或已损坏")
        }

        val headerBytes = ByteArray(headerLen)
        dis.readFully(headerBytes)
        val remaining = containerBytes.size - 8 - headerLen
        if (remaining < 16) {
            throw BackupException("备份密文已截断")
        }
        val ciphertext = ByteArray(remaining)
        dis.readFully(ciphertext)

        val headerObj = try {
            JSONObject(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份文件头解析失败", e)
        }

        val formatVersion = headerObj.optInt("formatVersion", -1)
        if (formatVersion != CONTAINER_VERSION) {
            throw BackupException("不支持的备份容器版本：$formatVersion")
        }
        val kdf = headerObj.optString("kdf", "")
        if (kdf != KDF_ALGORITHM) {
            throw BackupException("不支持的密钥派生算法：$kdf")
        }
        val cipherAlg = headerObj.optString("cipher", "")
        if (cipherAlg != CIPHER_ALGORITHM) {
            throw BackupException("不支持的加密算法：$cipherAlg")
        }
        val iterations = headerObj.optInt("iterations", -1)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException("备份文件 KDF 参数超出安全范围")
        }
        val tagBits = headerObj.optInt("tagBits", -1)
        if (tagBits != TAG_BITS) {
            throw BackupException("不支持的 GCM 标签长度：$tagBits")
        }
        val salt = runCatching { Base64.getDecoder().decode(headerObj.getString("saltB64")) }.getOrNull()
            ?: throw BackupException("备份盐值格式无效")
        val nonce = runCatching { Base64.getDecoder().decode(headerObj.getString("nonceB64")) }.getOrNull()
            ?: throw BackupException("备份 Nonce 格式无效")
        if (salt.size < 16 || nonce.size != NONCE_BYTES) {
            throw BackupException("备份加密参数长度异常")
        }

        val keyBytes = deriveKey(password, salt, iterations)
        val plaintext = try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(headerBytes)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw BackupException("密码错误或备份文件已被篡改/损坏", e)
        } finally {
            keyBytes.fill(0)
        }

        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("解密后备份 JSON 损坏", e)
        }
        val snapshot = deserializeAndValidateSnapshot(root)
        return snapshot
    }

    fun computeCurrencyChecks(transactions: List<Transaction>): List<CurrencyCheckTotal> {
        return transactions
            .groupBy { it.currency to it.scale }
            .entries
            .sortedBy { it.key.first }
            .map { (key, list) ->
                CurrencyCheckTotal(
                    currency = key.first,
                    scale = key.second,
                    txCount = list.size,
                    totalAmountMinor = list.sumOf { it.amountMinor }
                )
            }
    }

    internal fun serializeSnapshot(snapshot: BackupSnapshot): JSONObject {
        val checks = computeCurrencyChecks(snapshot.transactions)
        return JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("exportedAtMs", snapshot.exportedAtMs)
            put("profile", JSONObject().apply {
                put("realName", snapshot.profile.realName)
                put("aliases", JSONArray(snapshot.profile.aliases))
                put("ownCardTails", JSONArray(snapshot.profile.ownCardTails))
                put("brokers", JSONArray(snapshot.profile.brokers))
            })
            put("userRules", JSONArray().apply {
                for (r in snapshot.userRules) {
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("sourceScope", r.sourceScope)
                        put("normalizedMerchant", r.normalizedMerchant)
                        put("category", r.category)
                        put("ruleVersion", r.ruleVersion)
                        put("createdAtMs", r.createdAtMs)
                    })
                }
            })
            put("transactions", JSONArray().apply {
                for (tx in snapshot.transactions) put(txToJson(tx))
            })
            put("sources", JSONArray().apply {
                for (s in snapshot.sources) put(sourceToJson(s))
            })
            put("sourceLinks", JSONArray().apply {
                for (l in snapshot.sourceLinks) {
                    put(JSONObject().apply {
                        put("transactionId", l.transactionId)
                        put("sourceId", l.sourceId)
                        put("linkRole", l.linkRole.name)
                        put("candidateId", l.candidateId ?: JSONObject.NULL)
                    })
                }
            })
            put("changes", JSONArray().apply {
                for (ch in snapshot.changes) {
                    put(JSONObject().apply {
                        put("opId", ch.opId)
                        put("opType", ch.opType.name)
                        put("affectedTxIds", JSONArray(ch.affectedTxIds))
                        put("beforeRevisions", intMapToJson(ch.beforeRevisions))
                        put("afterRevisions", intMapToJson(ch.afterRevisions))
                        put("inversePayloadJson", ch.inversePayloadJson)
                        put("createdAtMs", ch.createdAtMs)
                    })
                }
            })
            put("pendingQueue", JSONArray().apply {
                for (q in snapshot.pendingQueue) put(offlineItemToJson(q))
            })
            put("currencyChecks", JSONArray().apply {
                for (c in checks) {
                    put(JSONObject().apply {
                        put("currency", c.currency)
                        put("scale", c.scale)
                        put("txCount", c.txCount)
                        put("totalAmountMinor", c.totalAmountMinor)
                    })
                }
            })
        }
    }

    internal fun deserializeAndValidateSnapshot(root: JSONObject): BackupSnapshot {
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
            throw BackupException("不支持的账本快照版本：$schemaVersion")
        }
        // Explicitly forbid credential keys from appearing inside a backup payload
        if (root.has("aiConfig") || root.has("apiKey") || root.has("ai_config")) {
            throw BackupException("备份内容包含不允许的凭据字段")
        }

        val pObj = root.optJSONObject("profile") ?: JSONObject()
        val profile = UserProfile(
            realName = pObj.optString("realName", ""),
            aliases = jsonArrayToStrings(pObj.optJSONArray("aliases")),
            ownCardTails = jsonArrayToStrings(pObj.optJSONArray("ownCardTails")),
            brokers = jsonArrayToStrings(pObj.optJSONArray("brokers")).ifEmpty { UserProfile().brokers }
        )

        val txArr = root.getJSONArray("transactions")
        if (txArr.length() > MAX_TRANSACTIONS) {
            throw BackupException("备份交易条数超出上限")
        }
        val transactions = ArrayList<Transaction>(txArr.length())
        val txIds = HashSet<String>(txArr.length())
        for (i in 0 until txArr.length()) {
            val tx = txFromJson(txArr.getJSONObject(i))
            if (!txIds.add(tx.id)) throw BackupException("备份中存在重复交易 ID：${tx.id}")
            transactions.add(tx)
        }

        val srcArr = root.getJSONArray("sources")
        val sources = ArrayList<TransactionSource>(srcArr.length())
        val srcIds = HashSet<String>(srcArr.length())
        for (i in 0 until srcArr.length()) {
            val s = sourceFromJson(srcArr.getJSONObject(i))
            if (!srcIds.add(s.id)) throw BackupException("备份中存在重复来源 ID：${s.id}")
            sources.add(s)
        }

        val linkArr = root.getJSONArray("sourceLinks")
        val links = ArrayList<TransactionSourceLink>(linkArr.length())
        for (i in 0 until linkArr.length()) {
            val obj = linkArr.getJSONObject(i)
            val txId = obj.getString("transactionId")
            val srcId = obj.getString("sourceId")
            if (txId !in txIds || srcId !in srcIds) {
                throw BackupException("备份外键校验失败：关联指向不存在的账单或来源")
            }
            links.add(
                TransactionSourceLink(
                    transactionId = txId,
                    sourceId = srcId,
                    linkRole = SourceLinkRole.valueOf(obj.getString("linkRole")),
                    candidateId = if (obj.isNull("candidateId")) null else obj.optString("candidateId").ifBlank { null }
                )
            )
        }

        val chArr = root.optJSONArray("changes") ?: JSONArray()
        val changes = ArrayList<TransactionChange>(chArr.length())
        for (i in 0 until chArr.length()) {
            val obj = chArr.getJSONObject(i)
            changes.add(
                TransactionChange(
                    opId = obj.getString("opId"),
                    opType = ChangeOpType.valueOf(obj.getString("opType")),
                    affectedTxIds = jsonArrayToStrings(obj.optJSONArray("affectedTxIds")),
                    beforeRevisions = jsonToIntMap(obj.optJSONObject("beforeRevisions")),
                    afterRevisions = jsonToIntMap(obj.optJSONObject("afterRevisions")),
                    inversePayloadJson = obj.optString("inversePayloadJson", "{}"),
                    createdAtMs = obj.getLong("createdAtMs")
                )
            )
        }

        val rulesArr = root.optJSONArray("userRules") ?: JSONArray()
        val userRules = ArrayList<UserMerchantRule>(rulesArr.length())
        for (i in 0 until rulesArr.length()) {
            val obj = rulesArr.getJSONObject(i)
            userRules.add(
                UserMerchantRule(
                    id = obj.getString("id"),
                    sourceScope = obj.optString("sourceScope", "*"),
                    normalizedMerchant = obj.getString("normalizedMerchant"),
                    category = obj.getString("category"),
                    ruleVersion = obj.optInt("ruleVersion", 1),
                    createdAtMs = obj.optLong("createdAtMs", 0L)
                )
            )
        }

        val queueArr = root.optJSONArray("pendingQueue") ?: JSONArray()
        val pendingQueue = ArrayList<OfflineQueueItem>(queueArr.length())
        for (i in 0 until queueArr.length()) {
            pendingQueue.add(offlineItemFromJson(queueArr.getJSONObject(i)))
        }

        val checksArr = root.getJSONArray("currencyChecks")
        val declaredChecks = ArrayList<CurrencyCheckTotal>(checksArr.length())
        for (i in 0 until checksArr.length()) {
            val obj = checksArr.getJSONObject(i)
            declaredChecks.add(
                CurrencyCheckTotal(
                    currency = obj.getString("currency"),
                    scale = obj.getInt("scale"),
                    txCount = obj.getInt("txCount"),
                    totalAmountMinor = obj.getLong("totalAmountMinor")
                )
            )
        }
        val actualChecks = computeCurrencyChecks(transactions)
        if (declaredChecks != actualChecks) {
            throw BackupException("备份分币种笔数或金额汇总校验不一致")
        }

        return BackupSnapshot(
            schemaVersion = schemaVersion,
            exportedAtMs = root.getLong("exportedAtMs"),
            profile = profile,
            userRules = userRules,
            transactions = transactions,
            sources = sources,
            sourceLinks = links,
            changes = changes,
            pendingQueue = pendingQueue,
            currencyChecks = actualChecks
        )
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw BackupException("密钥派生失败：${e.javaClass.simpleName}", e)
        } finally {
            spec.clearPassword()
        }
    }

    internal fun txToJson(tx: Transaction): JSONObject = JSONObject().apply {
        put("id", tx.id)
        put("occurredAtMs", tx.occurredAtMs)
        put("observedAtMs", tx.observedAtMs)
        put("updatedAtMs", tx.updatedAtMs)
        put("revision", tx.revision)
        put("direction", tx.direction.name)
        put("amountMinor", tx.amountMinor)
        put("currency", tx.currency)
        put("scale", tx.scale)
        put("settlementAmountMinor", tx.settlementAmountMinor ?: JSONObject.NULL)
        put("settlementCurrency", tx.settlementCurrency ?: JSONObject.NULL)
        put("settlementScale", tx.settlementScale)
        put("settlementIsEstimated", tx.settlementIsEstimated)
        put("merchant", tx.merchant)
        put("itemSummary", tx.itemSummary)
        put("category", tx.category)
        put("paymentMethod", tx.paymentMethod)
        put("sourceChannels", JSONArray(tx.sourceChannels))
        put("mergedNotifications", tx.mergedNotifications)
        put("status", tx.status.name)
        put("ignoreReason", tx.ignoreReason)
        put("deepLink", tx.deepLink)
        put("deepLinkLabel", tx.deepLinkLabel)
        put("aiModeUsed", tx.aiModeUsed)
        put("aiReasoning", tx.aiReasoning)
        put("relatedTxId", tx.relatedTxId ?: JSONObject.NULL)
        put("rescuedByUser", tx.rescuedByUser)
        put("legacyMergedCount", tx.legacyMergedCount)
        put("legacyMissingSources", tx.legacyMissingSources)
        // Intentionally omit raw/debug sanitizedText from backup container to minimize private text persistence
    }

    internal fun txFromJson(obj: JSONObject): Transaction {
        val scale = obj.getInt("scale")
        if (scale !in 0..6) throw BackupException("无效币种精度：$scale")
        val amountMinor = obj.getLong("amountMinor")
        if (amountMinor < 0L) throw BackupException("账单金额不可为负数")
        val currency = obj.getString("currency").trim()
        if (currency.isEmpty() || currency.length > 8) throw BackupException("无效币种代码：$currency")
        return Transaction(
            id = obj.getString("id"),
            occurredAtMs = obj.getLong("occurredAtMs"),
            observedAtMs = obj.optLong("observedAtMs", obj.getLong("occurredAtMs")),
            updatedAtMs = obj.optLong("updatedAtMs", obj.getLong("occurredAtMs")),
            revision = obj.optInt("revision", 1),
            direction = TxDirection.valueOf(obj.getString("direction")),
            amountMinor = amountMinor,
            currency = currency,
            scale = scale,
            settlementAmountMinor = if (obj.isNull("settlementAmountMinor")) null else obj.getLong("settlementAmountMinor"),
            settlementCurrency = if (obj.isNull("settlementCurrency")) null else obj.optString("settlementCurrency").ifBlank { null },
            settlementScale = obj.optInt("settlementScale", 2),
            settlementIsEstimated = obj.optBoolean("settlementIsEstimated", false),
            merchant = obj.getString("merchant"),
            itemSummary = obj.optString("itemSummary", ""),
            category = obj.optString("category", ""),
            paymentMethod = obj.optString("paymentMethod", ""),
            sourceChannels = jsonArrayToStrings(obj.optJSONArray("sourceChannels")),
            mergedNotifications = obj.optInt("mergedNotifications", 1),
            status = TxStatus.valueOf(obj.getString("status")),
            ignoreReason = obj.optString("ignoreReason", ""),
            deepLink = obj.optString("deepLink", ""),
            deepLinkLabel = obj.optString("deepLinkLabel", ""),
            sanitizedText = "",
            aiModeUsed = obj.optString("aiModeUsed", "local"),
            aiReasoning = obj.optString("aiReasoning", ""),
            relatedTxId = if (obj.isNull("relatedTxId")) null else obj.optString("relatedTxId").ifBlank { null },
            rescuedByUser = obj.optBoolean("rescuedByUser", false),
            legacyMergedCount = obj.optInt("legacyMergedCount", 0),
            legacyMissingSources = obj.optBoolean("legacyMissingSources", false)
        )
    }

    internal fun sourceToJson(s: TransactionSource): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("sourceType", s.sourceType)
        put("appPackage", s.appPackage)
        put("appName", s.appName)
        put("externalId", s.externalId ?: JSONObject.NULL)
        put("observedAtMs", s.observedAtMs)
        put("sourceEventTimeMs", s.sourceEventTimeMs ?: JSONObject.NULL)
        put("amountCandidates", AmountCandidate.listToJson(s.amountCandidates))
        put("matcherVersion", s.matcherVersion)
        put("shortFingerprint", s.shortFingerprint)
        put("cardTail", s.cardTail ?: JSONObject.NULL)
        put("merchantHint", s.merchantHint)
        put("channelLabel", s.channelLabel)
    }

    internal fun sourceFromJson(obj: JSONObject): TransactionSource = TransactionSource(
        id = obj.getString("id"),
        sourceType = obj.getString("sourceType"),
        appPackage = obj.getString("appPackage"),
        appName = obj.getString("appName"),
        externalId = if (obj.isNull("externalId")) null else obj.optString("externalId").ifBlank { null },
        observedAtMs = obj.getLong("observedAtMs"),
        sourceEventTimeMs = if (obj.isNull("sourceEventTimeMs")) null else obj.getLong("sourceEventTimeMs"),
        amountCandidates = AmountCandidate.listFromJson(obj.optJSONArray("amountCandidates")),
        matcherVersion = obj.optString("matcherVersion", "v3-local-1"),
        shortFingerprint = obj.optString("shortFingerprint", ""),
        cardTail = if (obj.isNull("cardTail")) null else obj.optString("cardTail").ifBlank { null },
        merchantHint = obj.optString("merchantHint", ""),
        channelLabel = obj.optString("channelLabel", "")
    )

    internal fun offlineItemToJson(item: OfflineQueueItem): JSONObject {
        val p = item.payload
        return JSONObject().apply {
            put("queueId", item.queueId)
            put("enqueuedAtMs", item.enqueuedAtMs)
            put("failures", item.failures)
            put("groupId", p.groupId)
            put("createdAtMs", p.createdAtMs)
            put("observedAtMs", p.observedAtMs)
            put("amountMinor", p.amountMinor ?: JSONObject.NULL)
            put("currency", p.currency)
            put("scale", p.scale)
            put("candidates", AmountCandidate.listToJson(p.candidates))
            put("primaryCandidateId", p.primaryCandidateId ?: JSONObject.NULL)
            put("feeCandidateId", p.feeCandidateId ?: JSONObject.NULL)
            put("splitParts", AmountCandidate.listToJson(p.splitParts))
            put("settlementCandidate", p.settlementCandidate?.toJson() ?: JSONObject.NULL)
            put("ambiguityReason", p.ambiguityReason ?: JSONObject.NULL)
            put("roughDirection", p.roughDirection)
            put("paymentMethod", p.paymentMethod)
            put("sourceChannels", JSONArray(p.sourceChannels))
            put("mergedNotifications", p.mergedNotifications)
            put("sanitizedText", p.sanitizedText)
            put("redactionNotes", JSONArray(p.redactionNotes))
            put("preInternalReason", p.preInternalReason ?: JSONObject.NULL)
            put("isZeroAuth", p.isZeroAuth)
            put("deepLink", p.deepLink)
            put("deepLinkLabel", p.deepLinkLabel)
            put("sources", JSONArray().apply { for (s in p.sources) put(sourceToJson(s)) })
        }
    }

    internal fun offlineItemFromJson(obj: JSONObject): OfflineQueueItem {
        val scale = obj.optInt("scale", 2).coerceIn(0, 6)
        val amountMinor = when {
            !obj.isNull("amountMinor") -> obj.getLong("amountMinor")
            !obj.isNull("amount") -> MoneyMath.doubleToMinor(obj.getDouble("amount"), scale)
            else -> null
        }
        val sourcesArr = obj.optJSONArray("sources")
        val sources = mutableListOf<TransactionSource>()
        if (sourcesArr != null) {
            for (i in 0 until sourcesArr.length()) {
                sources.add(sourceFromJson(sourcesArr.getJSONObject(i)))
            }
        }
        val payload = MergedPayload(
            groupId = obj.getString("groupId"),
            createdAtMs = obj.getLong("createdAtMs"),
            observedAtMs = obj.optLong("observedAtMs", obj.getLong("createdAtMs")),
            amountMinor = amountMinor,
            currency = obj.optString("currency", "CNY").ifBlank { "CNY" },
            scale = scale,
            candidates = AmountCandidate.listFromJson(obj.optJSONArray("candidates")),
            primaryCandidateId = if (obj.isNull("primaryCandidateId")) null else obj.optString("primaryCandidateId").ifBlank { null },
            feeCandidateId = if (obj.isNull("feeCandidateId")) null else obj.optString("feeCandidateId").ifBlank { null },
            splitParts = AmountCandidate.listFromJson(obj.optJSONArray("splitParts")),
            settlementCandidate = if (obj.isNull("settlementCandidate")) null else AmountCandidate.fromJson(obj.getJSONObject("settlementCandidate")),
            ambiguityReason = if (obj.isNull("ambiguityReason")) null else obj.optString("ambiguityReason").ifBlank { null },
            roughDirection = obj.getString("roughDirection"),
            paymentMethod = obj.getString("paymentMethod"),
            sourceChannels = jsonArrayToStrings(obj.optJSONArray("sourceChannels")),
            mergedNotifications = obj.optInt("mergedNotifications", 1),
            sanitizedText = obj.optString("sanitizedText", ""),
            redactionNotes = jsonArrayToStrings(obj.optJSONArray("redactionNotes")),
            preInternalReason = if (obj.isNull("preInternalReason")) null else obj.optString("preInternalReason").ifBlank { null },
            isZeroAuth = obj.optBoolean("isZeroAuth", false),
            deepLink = obj.optString("deepLink", ""),
            deepLinkLabel = obj.optString("deepLinkLabel", ""),
            sources = sources
        )
        return OfflineQueueItem(
            queueId = obj.getString("queueId"),
            enqueuedAtMs = obj.getLong("enqueuedAtMs"),
            payload = payload,
            failures = obj.optInt("failures", 0)
        )
    }

    internal fun intMapToJson(map: Map<String, Int>): JSONObject = JSONObject().apply {
        for ((k, v) in map) put(k, v)
    }

    internal fun jsonToIntMap(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.getInt(k)
        }
        return out
    }

    internal fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }
}
