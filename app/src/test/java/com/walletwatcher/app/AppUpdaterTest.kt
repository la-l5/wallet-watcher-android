package com.walletwatcher.app

import com.walletwatcher.app.core.ApkInstallPreflight
import com.walletwatcher.app.core.AppUpdater
import com.walletwatcher.app.core.HttpClientAdapter
import com.walletwatcher.app.core.HttpResponseData
import com.walletwatcher.app.core.ReleaseUpdateInfo
import com.walletwatcher.app.core.SemVer
import com.walletwatcher.app.core.UpdateCheckResult
import com.walletwatcher.app.core.UpdateSecurityException
import com.walletwatcher.app.core.VerifiedApkArchive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdaterTest {

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun semVerParsesDebugSuffixesAndComparesStableAndPrereleaseCorrectly() {
        val vBeta1Debug = SemVer.parse("0.2.0-beta.1-debug")!!
        val vBeta2 = SemVer.parse("v0.2.0-beta.2")!!
        val vBeta10 = SemVer.parse("v0.2.0-beta.10")!!
        val vStable = SemVer.parse("v0.2.0")!!
        val vNext = SemVer.parse("v0.2.1")!!

        assertTrue(vBeta2 > vBeta1Debug)
        assertTrue(vBeta10 > vBeta2)
        assertTrue(vStable > vBeta10)
        assertTrue(vNext > vStable)
        assertEquals(0, SemVer.parse("0.2.0-debug")!!.compareTo(vStable))
        assertNull(SemVer.parse("not-a-version"))
    }

    @Test
    fun evaluatesGitHubReleasesJsonRespectingDraftPrereleaseAndRequiredAssets() {
        val json = """
            [
              {
                "tag_name": "v0.3.0",
                "draft": true,
                "prerelease": false,
                "name": "Draft Release",
                "html_url": "https://github.com/la-l5/wallet-watcher-android/releases/tag/v0.3.0",
                "assets": [
                  {"name": "wallet-watcher-release.apk", "size": 5000000, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.3.0/wallet-watcher-release.apk"},
                  {"name": "checksums.txt", "size": 500, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.3.0/checksums.txt"}
                ]
              },
              {
                "tag_name": "v0.2.1-beta.1",
                "draft": false,
                "prerelease": true,
                "name": "Beta 0.2.1",
                "body": "新增手动联网更新",
                "html_url": "https://github.com/la-l5/wallet-watcher-android/releases/tag/v0.2.1-beta.1",
                "assets": [
                  {"name": "wallet-watcher-release.apk", "size": 4200000, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.1-beta.1/wallet-watcher-release.apk"},
                  {"name": "checksums.txt", "size": 420, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.1-beta.1/checksums.txt"}
                ]
              },
              {
                "tag_name": "v0.2.0",
                "draft": false,
                "prerelease": false,
                "name": "Stable 0.2.0",
                "html_url": "https://github.com/la-l5/wallet-watcher-android/releases/tag/v0.2.0",
                "assets": [
                  {"name": "wallet-watcher-release.apk", "size": 4000000, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.0/wallet-watcher-release.apk"},
                  {"name": "checksums.txt", "size": 400, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.0/checksums.txt"}
                ]
              },
              {
                "tag_name": "v0.9.0",
                "draft": false,
                "prerelease": false,
                "name": "Missing Checksum Release",
                "html_url": "https://github.com/la-l5/wallet-watcher-android/releases/tag/v0.9.0",
                "assets": [
                  {"name": "wallet-watcher-release.apk", "size": 4000000, "browser_download_url": "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.9.0/wallet-watcher-release.apk"}
                ]
              }
            ]
        """.trimIndent()

        // Stable user on 0.2.0 ignores draft (0.3.0), prerelease (0.2.1-beta.1), and checksum-less release (0.9.0) -> UpToDate
        val stableCheck = AppUpdater.evaluateReleasesJson(json, SemVer.parse("0.2.0")!!, includePrerelease = false)
        assertEquals(UpdateCheckResult.UpToDate("v0.2.0"), stableCheck)

        // Beta user on 0.2.0-beta.2 gets v0.2.1-beta.1
        val betaCheck = AppUpdater.evaluateReleasesJson(json, SemVer.parse("0.2.0-beta.2-debug")!!, includePrerelease = true)
        assertTrue(betaCheck is UpdateCheckResult.UpdateAvailable)
        assertEquals("v0.2.1-beta.1", (betaCheck as UpdateCheckResult.UpdateAvailable).release.tagName)
    }

    @Test
    fun parsesChecksumsTxtStrictlyAndRejectsMissingOrAmbiguousHashes() {
        val goodHex = "a".repeat(64)
        val txt = """
            # Wallet Watcher Release Checksums

            Version Name: 0.2.0-beta.2
            Version Code: 3

            ## APK File SHA-256 (verify download integrity)
            $goodHex  wallet-watcher-release.apk

            ## APK Signing Certificate SHA-256 Fingerprint (verify publisher identity)
            Signer #1 certificate SHA-256 digest: ${"b".repeat(64)}
        """.trimIndent()

        assertEquals(goodHex, AppUpdater.parseExpectedApkSha256(txt, "wallet-watcher-release.apk"))
        assertNull(AppUpdater.parseExpectedApkSha256(txt, "other.apk"))

        // Legacy relative path prefix "release-dist/wallet-watcher-release.apk" also supported cleanly
        assertEquals(goodHex, AppUpdater.parseExpectedApkSha256("$goodHex  release-dist/wallet-watcher-release.apk\n", "wallet-watcher-release.apk"))

        // Conflicting duplicate hashes for the same filename -> fail closed (null)
        val conflicting = "$goodHex  wallet-watcher-release.apk\n${"c".repeat(64)}  wallet-watcher-release.apk"
        assertNull(AppUpdater.parseExpectedApkSha256(conflicting, "wallet-watcher-release.apk"))
    }

    @Test
    fun urlAndRedirectValidationRejectsHttpForeignHostsUserInfoPortsAndTraversal() {
        assertTrue(AppUpdater.isAllowedApiUrl(AppUpdater.RELEASES_API_URL))
        assertTrue(AppUpdater.isAllowedInitialAssetUrl("https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.0/wallet-watcher-release.apk"))
        assertTrue(AppUpdater.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123?sig=abc"))
        assertTrue(AppUpdater.isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/123"))

        // Reject HTTP, spoofed subdomains, arbitrary hosts, userinfo, non-443 ports, path traversal
        assertFalse(AppUpdater.isAllowedApiUrl("http://api.github.com/repos/la-l5/wallet-watcher-android/releases"))
        assertFalse(AppUpdater.isAllowedApiUrl("https://api.github.com/repos/evil/other/releases"))
        assertFalse(AppUpdater.isAllowedApiUrl("https://api.github.com/repos/la-l5/wallet-watcher-android/releases/../../evil/repo/releases"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://github.com.evil.com/x.apk"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://github.com@evil.com/x.apk"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://github.com:8443/x.apk"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://raw.githubusercontent.com/la-l5/wallet-watcher-android/main/x.apk"))
        assertFalse(AppUpdater.isAllowedInitialAssetUrl("https://github.com/la-l5/wallet-watcher-android/releases/download/../../other/repo/releases/download/v1/a.apk"))

        assertThrows(UpdateSecurityException::class.java) {
            AppUpdater.resolveSafeRedirect(
                "https://github.com/la-l5/wallet-watcher-android/releases/download/v1/a.apk",
                "https://evil.example.com/a.apk",
                allowDownloadHosts = true
            )
        }
    }

    @Test
    fun downloadAndVerifyApkFollowsSafeRedirectPassesOnMatchAndDeletesOnTamperOrOversize() {
        val tempDir = Files.createTempDirectory("ww_updater_test").toFile()
        try {
            val fakeApkBytes = "PK\u0003\u0004fake-signed-apk-payload-0123456789".toByteArray()
            val goodHash = sha256Hex(fakeApkBytes)
            val release = ReleaseUpdateInfo(
                tagName = "v0.2.1",
                version = SemVer.parse("v0.2.1")!!,
                title = "v0.2.1",
                notes = "",
                isPrerelease = false,
                htmlUrl = "https://github.com/la-l5/wallet-watcher-android/releases/tag/v0.2.1",
                apkAssetName = "wallet-watcher-release.apk",
                apkDownloadUrl = "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.1/wallet-watcher-release.apk",
                apkSizeBytes = fakeApkBytes.size.toLong(),
                checksumDownloadUrl = "https://github.com/la-l5/wallet-watcher-android/releases/download/v0.2.1/checksums.txt"
            )

            // 1. Happy path with realistic GitHub 302 -> release-assets.githubusercontent.com redirect
            val goodClient = HttpClientAdapter { url, _ ->
                when (url) {
                    release.checksumDownloadUrl -> HttpResponseData(
                        200, null, -1L,
                        ByteArrayInputStream("$goodHash  wallet-watcher-release.apk\n".toByteArray())
                    )
                    release.apkDownloadUrl -> HttpResponseData(
                        302, "https://release-assets.githubusercontent.com/signed-download/apk", 0L,
                        ByteArrayInputStream(ByteArray(0))
                    )
                    "https://release-assets.githubusercontent.com/signed-download/apk" -> HttpResponseData(
                        200, null, fakeApkBytes.size.toLong(),
                        ByteArrayInputStream(fakeApkBytes)
                    )
                    else -> error("Unexpected URL: $url")
                }
            }
            var progressCalls = 0
            val (file, verifiedHash) = AppUpdater.downloadAndVerifyApk(release, tempDir, httpClient = goodClient) { _, _ ->
                progressCalls++
            }
            assertEquals(goodHash, verifiedHash)
            assertTrue(file.isFile)
            assertArrayEquals(fakeApkBytes, file.readBytes())
            assertTrue(progressCalls > 0)
            assertFalse(File(tempDir, "${AppUpdater.UPDATE_APK_FILENAME}.part").exists())

            // 2. Tampered APK bytes -> MUST fail closed and delete both .part and final file!
            val tamperedClient = HttpClientAdapter { url, _ ->
                when (url) {
                    release.checksumDownloadUrl -> HttpResponseData(
                        200, null, -1L,
                        ByteArrayInputStream("$goodHash  wallet-watcher-release.apk\n".toByteArray())
                    )
                    release.apkDownloadUrl -> HttpResponseData(
                        200, null, fakeApkBytes.size.toLong(),
                        ByteArrayInputStream("PK\u0003\u0004TAMPERED-PAYLOAD".toByteArray())
                    )
                    else -> error("Unexpected URL: $url")
                }
            }
            assertThrows(UpdateSecurityException::class.java) {
                AppUpdater.downloadAndVerifyApk(release, tempDir, httpClient = tamperedClient)
            }
            assertFalse(File(tempDir, AppUpdater.UPDATE_APK_FILENAME).exists())
            assertFalse(File(tempDir, "${AppUpdater.UPDATE_APK_FILENAME}.part").exists())

            // 3. Redirect to untrusted third-party host -> rejected before reading body!
            val hijackClient = HttpClientAdapter { url, _ ->
                when (url) {
                    release.checksumDownloadUrl -> HttpResponseData(
                        200, null, -1L,
                        ByteArrayInputStream("$goodHash  wallet-watcher-release.apk\n".toByteArray())
                    )
                    release.apkDownloadUrl -> HttpResponseData(
                        302, "https://attacker.invalid/malicious.apk", 0L,
                        ByteArrayInputStream(ByteArray(0))
                    )
                    else -> error("Should never follow untrusted redirect: $url")
                }
            }
            assertThrows(UpdateSecurityException::class.java) {
                AppUpdater.downloadAndVerifyApk(release, tempDir, httpClient = hijackClient)
            }
            assertFalse(File(tempDir, AppUpdater.UPDATE_APK_FILENAME).exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installPreflightEnforcesPackageNameVersionIncreaseCertMatchAndTrustedDebugFirstInstall() {
        val dummyFile = File("dummy.apk")
        val certOfficial = "11".repeat(32)
        val certEvil = "22".repeat(32)
        val certDebug = "33".repeat(32)
        val validSha = "a".repeat(64)

        // 1. Wrong package name -> blocked
        val wrongPkg = VerifiedApkArchive(dummyFile, validSha, "com.evil.fake", 10L, "1.0.0", setOf(certOfficial))
        assertTrue(AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), wrongPkg) is ApkInstallPreflight.Blocked)

        // 2. Non-positive versionCode -> blocked on both release and debug
        val zeroVer = VerifiedApkArchive(dummyFile, validSha, "com.walletwatcher.app", 0L, "0.2.0-beta.3", setOf(certOfficial))
        assertTrue(AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), zeroVer) is ApkInstallPreflight.Blocked)
        assertTrue(
            AppUpdater.evaluateInstallPreflight(
                "com.walletwatcher.app.debug",
                3L,
                setOf(certDebug),
                zeroVer,
                trustedReleaseCertSha256s = setOf(certOfficial)
            ) is ApkInstallPreflight.Blocked
        )

        // 3. Downgrade / same versionCode on release -> blocked
        val sameVer = VerifiedApkArchive(dummyFile, validSha, "com.walletwatcher.app", 3L, "0.2.0-beta.2", setOf(certOfficial))
        assertTrue(AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), sameVer) is ApkInstallPreflight.Blocked)

        // 4. Different signing cert on release -> blocked with clear guidance
        val wrongCert = VerifiedApkArchive(dummyFile, validSha, "com.walletwatcher.app", 4L, "0.2.0-beta.3", setOf(certEvil))
        assertTrue(AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), wrongCert) is ApkInstallPreflight.Blocked)

        // 5. Empty signing cert on release -> blocked
        val unsigned = VerifiedApkArchive(dummyFile, validSha, "com.walletwatcher.app", 4L, "0.2.0-beta.3", emptySet())
        assertTrue(AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), unsigned) is ApkInstallPreflight.Blocked)

        // 6. Same cert + higher versionCode on release -> ready to overwrite-upgrade!
        val goodUpgrade = VerifiedApkArchive(dummyFile, validSha, "com.walletwatcher.app", 4L, "0.2.0-beta.3", setOf(certOfficial))
        val upgradeRes = AppUpdater.evaluateInstallPreflight("com.walletwatcher.app", 3L, setOf(certOfficial), goodUpgrade)
        assertEquals(ApkInstallPreflight.ReadyToInstall(isCoexistInstallFromDebug = false), upgradeRes)

        // 7. Debug -> Release first install WITHOUT pinned trusted cert fingerprint -> fail closed (Blocked)
        val unpinnedDebug = AppUpdater.evaluateInstallPreflight(
            installedPackageName = "com.walletwatcher.app.debug",
            installedVersionCode = 3L,
            installedSigningCertSha256s = setOf(certDebug),
            archive = goodUpgrade,
            trustedReleaseCertSha256s = emptySet()
        )
        assertTrue("Debug build without pinned cert must fail closed", unpinnedDebug is ApkInstallPreflight.Blocked)

        // 8. Debug -> Release first install with correct packageName & SHA-256 but UNOFFICIAL cert -> Blocked!
        val evilFromDebug = AppUpdater.evaluateInstallPreflight(
            installedPackageName = "com.walletwatcher.app.debug",
            installedVersionCode = 3L,
            installedSigningCertSha256s = setOf(certDebug),
            archive = wrongCert,
            trustedReleaseCertSha256s = setOf(certOfficial)
        )
        assertTrue("Debug build must reject APK signed by untrusted cert", evilFromDebug is ApkInstallPreflight.Blocked)

        // 9. Debug -> Release first install with unsigned APK -> Blocked!
        val unsignedFromDebug = AppUpdater.evaluateInstallPreflight(
            installedPackageName = "com.walletwatcher.app.debug",
            installedVersionCode = 3L,
            installedSigningCertSha256s = setOf(certDebug),
            archive = unsigned,
            trustedReleaseCertSha256s = setOf(certOfficial)
        )
        assertTrue("Debug build must reject unsigned APK", unsignedFromDebug is ApkInstallPreflight.Blocked)

        // 10. Debug -> Release first install with OFFICIAL pinned cert -> ReadyToInstall (coexist)
        val officialFromDebug = AppUpdater.evaluateInstallPreflight(
            installedPackageName = "com.walletwatcher.app.debug",
            installedVersionCode = 3L,
            installedSigningCertSha256s = setOf(certDebug),
            archive = goodUpgrade,
            trustedReleaseCertSha256s = setOf(certOfficial)
        )
        assertEquals(ApkInstallPreflight.ReadyToInstall(isCoexistInstallFromDebug = true), officialFromDebug)
    }

    @Test
    fun parsesTrustedReleaseCertFingerprintsAndGatesDebugInAppInstall() {
        val hex1 = "11".repeat(32)
        val hex2 = "ab".repeat(32)
        val colonUpper = hex2.uppercase().chunked(2).joinToString(":")

        assertTrue(AppUpdater.parseTrustedCertFingerprints("").isEmpty())
        assertTrue(AppUpdater.parseTrustedCertFingerprints("   ").isEmpty())
        assertEquals(setOf(hex2), AppUpdater.parseTrustedCertFingerprints(colonUpper))
        assertEquals(setOf(hex1, hex2), AppUpdater.parseTrustedCertFingerprints("$hex1, $colonUpper"))
        // Fail closed if ANY entry is malformed
        assertTrue(AppUpdater.parseTrustedCertFingerprints("$hex1,not-a-valid-sha256").isEmpty())
        assertTrue(AppUpdater.parseTrustedCertFingerprints("1234").isEmpty())

        // Debug builds require a valid pinned release fingerprint; Release builds always allow in-app upgrade check
        assertFalse(AppUpdater.canInstallReleaseFromBuild(isDebugBuild = true, trustedReleaseCertSha256Raw = ""))
        assertFalse(AppUpdater.canInstallReleaseFromBuild(isDebugBuild = true, trustedReleaseCertSha256Raw = "invalid"))
        assertTrue(AppUpdater.canInstallReleaseFromBuild(isDebugBuild = true, trustedReleaseCertSha256Raw = colonUpper))
        assertTrue(AppUpdater.canInstallReleaseFromBuild(isDebugBuild = false, trustedReleaseCertSha256Raw = ""))

        // Verify generated BuildConfig field is wired and parses without error
        assertNotNull(AppUpdater.parseTrustedCertFingerprints(BuildConfig.TRUSTED_RELEASE_CERT_SHA256))
    }
}
