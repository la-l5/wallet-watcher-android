package com.walletwatcher.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class UpdateSecurityException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList()
) : Comparable<SemVer> {

    val isPrerelease: Boolean
        get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1 // 1.0.0 > 1.0.0-beta.1
        if (other.prerelease.isEmpty()) return -1
        val maxLen = maxOf(prerelease.size, other.prerelease.size)
        for (i in 0 until maxLen) {
            val a = prerelease.getOrNull(i) ?: return -1
            val b = other.prerelease.getOrNull(i) ?: return 1
            if (a == b) continue
            val aNum = a.toLongOrNull()
            val bNum = b.toLongOrNull()
            return when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1 // SemVer 2.0: numeric identifiers have lower precedence than non-numeric
                bNum != null -> 1
                else -> a.compareTo(b)
            }
        }
        return 0
    }

    companion object {
        private val SEMVER_REGEX = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(raw: String): SemVer? {
            var cleaned = raw.trim()
            if (cleaned.endsWith("-debug", ignoreCase = true)) {
                cleaned = cleaned.dropLast("-debug".length)
            }
            val m = SEMVER_REGEX.matchEntire(cleaned) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            val preRaw = m.groupValues[4]
            val pre = if (preRaw.isBlank()) emptyList() else preRaw.split('.').filter { it.isNotEmpty() }
            return SemVer(major, minor, patch, pre)
        }
    }
}

data class ReleaseUpdateInfo(
    val tagName: String,
    val version: SemVer,
    val title: String,
    val notes: String,
    val isPrerelease: Boolean,
    val htmlUrl: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val checksumDownloadUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val latestTag: String?) : UpdateCheckResult()
    object NoReleasePublished : UpdateCheckResult()
}

data class VerifiedApkArchive(
    val file: File,
    val sha256Hex: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signingCertSha256s: Set<String>
)

sealed class ApkInstallPreflight {
    data class ReadyToInstall(val isCoexistInstallFromDebug: Boolean) : ApkInstallPreflight()
    data class Blocked(val reason: String) : ApkInstallPreflight()
}

data class HttpResponseData(
    val statusCode: Int,
    val locationHeader: String?,
    val contentLength: Long,
    val stream: InputStream,
    private val onClose: () -> Unit = {}
) : Closeable {
    override fun close() {
        runCatching { stream.close() }
        runCatching { onClose() }
    }
}

fun interface HttpClientAdapter {
    fun get(url: String, headers: Map<String, String>): HttpResponseData
}

/**
 * Strictly manual, SHA-256-verified GitHub Releases updater.
 * - NEVER runs automatically or in the background; invoked ONLY on explicit user click.
 * - Enforces HTTPS + exact GitHub host allowlist on every request AND every redirect hop.
 * - Requires a matching 64-hex SHA-256 from checksums.txt in the SAME release (fail-closed).
 * - Enforces strict max download limits (64 KB checksums, 64 MB APK) and atomic .part -> .apk write.
 */
object AppUpdater {

    const val GITHUB_OWNER = "la-l5"
    const val GITHUB_REPO_NAME = "wallet-watcher-android"
    const val GITHUB_REPO = "$GITHUB_OWNER/$GITHUB_REPO_NAME"
    const val EXPECTED_RELEASE_PACKAGE = "com.walletwatcher.app"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases"
    const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=30"

    const val MAX_REDIRECTS = 5
    const val MAX_API_BYTES = 512 * 1024L // 512 KB
    const val MAX_CHECKSUM_BYTES = 64 * 1024L // 64 KB
    const val MAX_APK_BYTES = 64L * 1024L * 1024L // 64 MB
    const val UPDATE_DIR_NAME = "updates"
    const val UPDATE_APK_FILENAME = "wallet-watcher-update.apk"

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    private val ALLOWED_API_HOSTS = setOf("api.github.com")
    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )
    private val ALLOWED_HTML_PREFIX = "https://github.com/$GITHUB_REPO/releases/"
    private val HEX_64_REGEX = Regex("""^[0-9a-fA-F]{64}$""")

    val defaultHttpClient: HttpClientAdapter = HttpClientAdapter { urlStr, headers ->
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location")
        val contentLen = conn.contentLengthLong
        val stream = if (code in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        HttpResponseData(
            statusCode = code,
            locationHeader = location,
            contentLength = contentLen,
            stream = stream,
            onClose = { conn.disconnect() }
        )
    }

    fun isAllowedApiUrl(urlStr: String): Boolean {
        val uri = runCatching { URI(urlStr) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host !in ALLOWED_API_HOSTS) return false
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.contains("..") || rawPath.contains("%2e", ignoreCase = true)) return false
        val normPath = uri.normalize().rawPath.orEmpty()
        return normPath.startsWith("/repos/$GITHUB_REPO/releases")
    }

    fun isAllowedInitialAssetUrl(urlStr: String): Boolean {
        if (!isAllowedDownloadUrl(urlStr)) return false
        val uri = runCatching { URI(urlStr) }.getOrNull() ?: return false
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.contains("..") || rawPath.contains("%2e", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val normPath = uri.normalize().rawPath.orEmpty()
        return (host == "github.com" && normPath.startsWith("/$GITHUB_REPO/releases/download/")) ||
            (host == "api.github.com" && normPath.startsWith("/repos/$GITHUB_REPO/releases/assets/"))
    }

    fun isAllowedDownloadUrl(urlStr: String): Boolean {
        val uri = runCatching { URI(urlStr) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in ALLOWED_DOWNLOAD_HOSTS
    }

    fun isAllowedReleaseHtmlUrl(urlStr: String): Boolean {
        if (!isAllowedDownloadUrl(urlStr)) return false
        val uri = runCatching { URI(urlStr) }.getOrNull() ?: return false
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.contains("..") || rawPath.contains("%2e", ignoreCase = true)) return false
        val norm = uri.normalize().toString()
        return norm == RELEASES_PAGE_URL || norm.startsWith(ALLOWED_HTML_PREFIX)
    }

    internal fun resolveSafeRedirect(currentUrl: String, location: String, allowDownloadHosts: Boolean): String {
        val resolved = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull()
            ?: throw UpdateSecurityException("无效的重定向地址")
        val ok = if (allowDownloadHosts) isAllowedDownloadUrl(resolved) else isAllowedApiUrl(resolved)
        if (!ok) {
            throw UpdateSecurityException("重定向目标不在受信任的 GitHub HTTPS 域名白名单内")
        }
        return resolved
    }

    /**
     * Checks GitHub Releases for a newer release matching the channel policy.
     * By default, prerelease builds (e.g. 0.2.0-beta.2) track prereleases + stable;
     * stable builds track stable releases only unless includePrerelease is explicitly true.
     */
    fun checkForUpdate(
        currentVersionName: String,
        includePrerelease: Boolean = SemVer.parse(currentVersionName)?.isPrerelease == true,
        httpClient: HttpClientAdapter = defaultHttpClient
    ): UpdateCheckResult {
        val currentVer = SemVer.parse(currentVersionName)
            ?: throw IllegalArgumentException("无法解析当前版本号：$currentVersionName")
        val jsonText = fetchTextStrict(
            url = RELEASES_API_URL,
            maxBytes = MAX_API_BYTES,
            allowDownloadHosts = false,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "WalletWatcher-Android/$currentVersionName"
            ),
            httpClient = httpClient
        )
        return evaluateReleasesJson(jsonText, currentVer, includePrerelease)
    }

    internal fun evaluateReleasesJson(
        releasesJsonArrayText: String,
        currentVersion: SemVer,
        includePrerelease: Boolean
    ): UpdateCheckResult {
        val arr = JSONArray(releasesJsonArrayText)
        val candidates = mutableListOf<ReleaseUpdateInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val isPre = obj.optBoolean("prerelease", false)
            if (isPre && !includePrerelease) continue

            val tagName = obj.optString("tag_name", "").trim()
            val ver = SemVer.parse(tagName) ?: continue
            val htmlUrl = obj.optString("html_url", "").trim().takeIf { isAllowedReleaseHtmlUrl(it) }
                ?: RELEASES_PAGE_URL

            val assets = obj.optJSONArray("assets") ?: continue
            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = -1L
            var checksumUrl: String? = null

            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val name = asset.optString("name", "").trim()
                val dl = asset.optString("browser_download_url", "").trim()
                if (!isAllowedInitialAssetUrl(dl)) continue
                when {
                    name.equals("checksums.txt", ignoreCase = true) -> {
                        checksumUrl = dl
                    }
                    name.endsWith(".apk", ignoreCase = true) -> {
                        // Prefer canonical wallet-watcher-release.apk if multiple APKs exist
                        val preferThis = apkName == null ||
                            name.equals("wallet-watcher-release.apk", ignoreCase = true)
                        if (preferThis) {
                            apkName = name
                            apkUrl = dl
                            apkSize = asset.optLong("size", -1L)
                        }
                    }
                }
            }

            if (apkName == null || apkUrl == null || checksumUrl == null) continue
            if (apkSize == 0L || apkSize > MAX_APK_BYTES) continue

            val title = obj.optString("name", "").trim().ifBlank { tagName }
            val notes = obj.optString("body", "").trim().take(1200)
            candidates.add(
                ReleaseUpdateInfo(
                    tagName = tagName,
                    version = ver,
                    title = title,
                    notes = notes,
                    isPrerelease = isPre,
                    htmlUrl = htmlUrl,
                    apkAssetName = apkName,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    checksumDownloadUrl = checksumUrl
                )
            )
        }

        val best = candidates.maxByOrNull { it.version } ?: return UpdateCheckResult.NoReleasePublished
        return if (best.version > currentVersion) {
            UpdateCheckResult.UpdateAvailable(best)
        } else {
            UpdateCheckResult.UpToDate(best.tagName)
        }
    }

    /**
     * Parses the exact 64-hex SHA-256 bound to [apkAssetName] from release checksums.txt.
     * Fails closed (returns null) if absent or ambiguous; ignores signing cert colon-hex fingerprints.
     */
    fun parseExpectedApkSha256(checksumsText: String, apkAssetName: String): String? {
        val lineRegex = Regex("""^\s*([0-9a-fA-F]{64})\s+\*?(?:.*/)?${Regex.escape(apkAssetName)}\s*$""")
        val matched = LinkedHashSet<String>()
        for (line in checksumsText.lineSequence()) {
            val m = lineRegex.matchEntire(line) ?: continue
            val hex = m.groupValues[1].lowercase(Locale.US)
            if (HEX_64_REGEX.matches(hex)) {
                matched.add(hex)
            }
        }
        return if (matched.size == 1) matched.single() else null
    }

    /**
     * Downloads checksums.txt + APK into [targetDir], verifying streaming SHA-256 byte-for-byte
     * before atomically moving .part -> wallet-watcher-update.apk. Deletes partial files on any failure.
     */
    fun downloadAndVerifyApk(
        release: ReleaseUpdateInfo,
        targetDir: File,
        userAgentVersion: String = "0.2.0",
        httpClient: HttpClientAdapter = defaultHttpClient,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Pair<File, String> {
        if (!isAllowedInitialAssetUrl(release.checksumDownloadUrl)) {
            throw UpdateSecurityException("校验文件地址不在受信任的 GitHub Release 路径下")
        }
        if (!isAllowedInitialAssetUrl(release.apkDownloadUrl)) {
            throw UpdateSecurityException("安装包地址不在受信任的 GitHub Release 路径下")
        }
        if (shouldCancel()) throw IOException("下载已取消")

        val headers = mapOf("User-Agent" to "WalletWatcher-Android/$userAgentVersion")
        val checksumsText = fetchTextStrict(
            url = release.checksumDownloadUrl,
            maxBytes = MAX_CHECKSUM_BYTES,
            allowDownloadHosts = true,
            headers = headers,
            httpClient = httpClient,
            shouldCancel = shouldCancel
        )
        val expectedSha256 = parseExpectedApkSha256(checksumsText, release.apkAssetName)
            ?: throw UpdateSecurityException("checksums.txt 中未找到 ${release.apkAssetName} 的有效 SHA-256，已中止安装")

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("无法创建更新缓存目录")
        }
        val finalFile = File(targetDir, UPDATE_APK_FILENAME)
        val partFile = File(targetDir, "$UPDATE_APK_FILENAME.part")
        if (partFile.exists()) partFile.delete()
        if (finalFile.exists()) finalFile.delete()

        try {
            val computedSha256 = downloadStreamToFileWithSha256(
                initialUrl = release.apkDownloadUrl,
                destFile = partFile,
                maxBytes = MAX_APK_BYTES,
                expectedSizeHint = release.apkSizeBytes,
                headers = headers,
                httpClient = httpClient,
                shouldCancel = shouldCancel,
                onProgress = onProgress
            )
            if (!MessageDigest.isEqual(
                    expectedSha256.toByteArray(Charsets.US_ASCII),
                    computedSha256.toByteArray(Charsets.US_ASCII)
                )
            ) {
                throw UpdateSecurityException("安装包 SHA-256 校验不匹配（文件可能损坏或被篡改），已自动删除")
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            return finalFile to computedSha256
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            runCatching { finalFile.delete() }
            throw t
        }
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun inspectApkArchive(context: Context, apkFile: File, verifiedSha256Hex: String): VerifiedApkArchive {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw UpdateSecurityException("安装包文件不存在或为空")
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw UpdateSecurityException("无法解析 APK 安装包元数据，文件可能损坏")
        val pkgName = info.packageName?.trim().orEmpty()
        val versionCode = extractVersionCode(info)
        val versionName = info.versionName?.trim().orEmpty()
        val certs = extractSigningCertSha256s(info)
        return VerifiedApkArchive(
            file = apkFile,
            sha256Hex = verifiedSha256Hex,
            packageName = pkgName,
            versionCode = versionCode,
            versionName = versionName,
            signingCertSha256s = certs
        )
    }

    /**
     * Parses comma-separated 64-hex SHA-256 signing certificate fingerprints (colons/whitespace allowed).
     * Fails closed (returns emptySet) if blank or if ANY entry is malformed.
     */
    fun parseTrustedCertFingerprints(raw: String): Set<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (part in trimmed.split(',')) {
            val normalized = part.replace(":", "").filterNot { it.isWhitespace() }.lowercase(Locale.US)
            if (!HEX_64_REGEX.matches(normalized)) {
                return emptySet()
            }
            out.add(normalized)
        }
        return out
    }

    /**
     * Release builds can always verify upgrades against their own installed signing certificate.
     * Debug (.debug) builds can only run in-app first-time Release install when an official
     * Release signing certificate SHA-256 fingerprint is pinned in version.properties.
     */
    fun canInstallReleaseFromBuild(isDebugBuild: Boolean, trustedReleaseCertSha256Raw: String): Boolean {
        if (!isDebugBuild) return true
        return parseTrustedCertFingerprints(trustedReleaseCertSha256Raw).isNotEmpty()
    }

    fun evaluateInstallPreflight(
        installedPackageName: String,
        installedVersionCode: Long,
        installedSigningCertSha256s: Set<String>,
        archive: VerifiedApkArchive,
        trustedReleaseCertSha256s: Set<String> = emptySet()
    ): ApkInstallPreflight {
        if (archive.packageName != EXPECTED_RELEASE_PACKAGE) {
            return ApkInstallPreflight.Blocked("安装包包名异常（${archive.packageName}），已拒绝安装")
        }
        if (archive.versionCode <= 0L) {
            return ApkInstallPreflight.Blocked("安装包 versionCode（${archive.versionCode}）异常，已拒绝安装")
        }
        val normalizedTrusted = trustedReleaseCertSha256s
            .map { it.replace(":", "").filterNot { ch -> ch.isWhitespace() }.lowercase(Locale.US) }
            .filter { HEX_64_REGEX.matches(it) }
            .toSet()
        val normalizedArchiveCerts = archive.signingCertSha256s
            .map { it.replace(":", "").filterNot { ch -> ch.isWhitespace() }.lowercase(Locale.US) }
            .filter { HEX_64_REGEX.matches(it) }
            .toSet()
        val normalizedInstalledCerts = installedSigningCertSha256s
            .map { it.replace(":", "").filterNot { ch -> ch.isWhitespace() }.lowercase(Locale.US) }
            .filter { HEX_64_REGEX.matches(it) }
            .toSet()

        val isCoexistFromDebug = installedPackageName != EXPECTED_RELEASE_PACKAGE
        if (isCoexistFromDebug) {
            if (normalizedTrusted.isEmpty()) {
                return ApkInstallPreflight.Blocked(
                    "当前调试版未配置官方受信任正式签名证书指纹，为防止首次安装被篡改，请改用浏览器打开 GitHub Releases 核对签名指纹后手动安装。"
                )
            }
            if (normalizedArchiveCerts.isEmpty()) {
                return ApkInstallPreflight.Blocked(
                    "无法验证新安装包的签名证书，已拒绝从调试版首次安装正式版。"
                )
            }
            if (normalizedArchiveCerts.intersect(normalizedTrusted).isEmpty()) {
                return ApkInstallPreflight.Blocked(
                    "新安装包签名证书与官方受信任正式证书指纹不一致，已拒绝安装。"
                )
            }
            return ApkInstallPreflight.ReadyToInstall(isCoexistInstallFromDebug = true)
        }

        if (archive.versionCode <= installedVersionCode) {
            return ApkInstallPreflight.Blocked(
                "下载包 versionCode(${archive.versionCode}) 未高于当前版本(${installedVersionCode})，无需覆盖安装"
            )
        }
        if (normalizedInstalledCerts.isEmpty() || normalizedArchiveCerts.isEmpty()) {
            return ApkInstallPreflight.Blocked(
                "无法验证已安装版本或新安装包的签名证书，已拒绝覆盖安装。"
            )
        }
        if (normalizedInstalledCerts.intersect(normalizedArchiveCerts).isEmpty()) {
            return ApkInstallPreflight.Blocked(
                "新安装包签名证书与当前已安装正式版不一致，无法直接覆盖更新。如确需更换签名，请先导出加密备份后卸载重装。"
            )
        }
        return ApkInstallPreflight.ReadyToInstall(isCoexistInstallFromDebug = false)
    }

    fun getInstalledSigningCertSha256s(context: Context): Set<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return emptySet()
        return extractSigningCertSha256s(info)
    }

    fun getInstalledVersionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return 0L
        return extractVersionCode(info)
    }

    fun getUpdatesCacheDir(context: Context): File = File(context.cacheDir, UPDATE_DIR_NAME)

    fun canRequestPackageInstalls(context: Context): Boolean {
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    fun buildUnknownAppSourcesIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun fetchTextStrict(
        url: String,
        maxBytes: Long,
        allowDownloadHosts: Boolean,
        headers: Map<String, String>,
        httpClient: HttpClientAdapter,
        shouldCancel: () -> Boolean = { false }
    ): String {
        val allowed = if (allowDownloadHosts) isAllowedDownloadUrl(url) else isAllowedApiUrl(url)
        if (!allowed) throw UpdateSecurityException("请求地址不在受信任的 GitHub HTTPS 白名单内")

        return executeWithSafeRedirects(url, allowDownloadHosts, headers, httpClient, shouldCancel) { resp ->
            if (resp.contentLength > maxBytes) {
                throw UpdateSecurityException("响应体超过允许上限（${maxBytes / 1024}KB）")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                if (shouldCancel()) throw IOException("下载已取消")
                val read = resp.stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw UpdateSecurityException("响应体超过允许上限（${maxBytes / 1024}KB）")
                }
                out.write(buf, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun downloadStreamToFileWithSha256(
        initialUrl: String,
        destFile: File,
        maxBytes: Long,
        expectedSizeHint: Long,
        headers: Map<String, String>,
        httpClient: HttpClientAdapter,
        shouldCancel: () -> Boolean,
        onProgress: ((Long, Long) -> Unit)?
    ): String {
        return executeWithSafeRedirects(initialUrl, allowDownloadHosts = true, headers = headers, httpClient = httpClient, shouldCancel = shouldCancel) { resp ->
            val totalHint = if (resp.contentLength > 0L) resp.contentLength else expectedSizeHint
            if (resp.contentLength > maxBytes) {
                throw UpdateSecurityException("安装包体积超过安全上限（${maxBytes / (1024 * 1024)}MB）")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            FileOutputStream(destFile).use { fos ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    if (shouldCancel()) {
                        throw IOException("下载已取消")
                    }
                    val read = resp.stream.read(buf)
                    if (read <= 0) break
                    downloaded += read
                    if (downloaded > maxBytes) {
                        throw UpdateSecurityException("安装包体积超过安全上限（${maxBytes / (1024 * 1024)}MB）")
                    }
                    fos.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    onProgress?.invoke(downloaded, totalHint)
                }
                fos.fd.sync()
            }
            if (downloaded <= 0L) {
                throw UpdateSecurityException("下载的安装包内容为空")
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun <T> executeWithSafeRedirects(
        initialUrl: String,
        allowDownloadHosts: Boolean,
        headers: Map<String, String>,
        httpClient: HttpClientAdapter,
        shouldCancel: () -> Boolean = { false },
        consumer: (HttpResponseData) -> T
    ): T {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (shouldCancel()) throw IOException("下载已取消")
            httpClient.get(currentUrl, headers).use { resp ->
                when (resp.statusCode) {
                    in 200..299 -> return consumer(resp)
                    301, 302, 303, 307, 308 -> {
                        if (hop >= MAX_REDIRECTS) {
                            throw UpdateSecurityException("重定向次数过多（超过 $MAX_REDIRECTS 次）")
                        }
                        val loc = resp.locationHeader?.trim()
                            ?: throw UpdateSecurityException("重定向响应缺少 Location 头")
                        currentUrl = resolveSafeRedirect(currentUrl, loc, allowDownloadHosts)
                    }
                    404 -> throw IOException("GitHub 返回 HTTP 404（仓库尚未公开，或对应 Release 资源不存在）")
                    else -> throw IOException("GitHub 返回异常状态码：HTTP ${resp.statusCode}")
                }
            }
        }
        throw UpdateSecurityException("重定向次数过多")
    }

    private fun extractVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun extractSigningCertSha256s(info: PackageInfo): Set<String> {
        val rawSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo
            if (si == null) {
                emptyArray()
            } else if (si.hasMultipleSigners()) {
                si.apkContentsSigners ?: emptyArray()
            } else {
                (si.signingCertificateHistory ?: si.apkContentsSigners) ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val out = LinkedHashSet<String>()
        for (sig in rawSigs) {
            val bytes = sig?.toByteArray() ?: continue
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            out.add(digest.joinToString("") { "%02x".format(it) })
        }
        return out
    }
}
