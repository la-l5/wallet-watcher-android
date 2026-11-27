import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    check(f.exists()) { "Missing required version.properties at repository root" }
    f.inputStream().use { load(it) }
}

fun propOrEnv(name: String, fallback: String = ""): String {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun quoted(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val strictSemVerRegex = Regex("""^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$""")
val sha256HexRegex = Regex("""^[0-9a-f]{64}$""")

val rawVersionCode = propOrEnv("APP_VERSION_CODE").ifBlank {
    versionProps.getProperty("VERSION_CODE")?.trim().orEmpty()
}
val appVersionCode = rawVersionCode.toIntOrNull()?.takeIf { it > 0 }
    ?: error("Invalid or missing versionCode ('$rawVersionCode'): must be a positive integer")

val appVersionName = propOrEnv("APP_VERSION_NAME").ifBlank {
    versionProps.getProperty("VERSION_NAME")?.trim().orEmpty()
}.also {
    check(strictSemVerRegex.matches(it)) {
        "Invalid or missing versionName ('$it'): must match SemVer (e.g. 0.2.0-beta.2)"
    }
}

val rawReleaseCertSha256 = versionProps.getProperty("RELEASE_CERT_SHA256")?.trim().orEmpty()
val trustedReleaseCertSha256 = if (rawReleaseCertSha256.isBlank()) {
    ""
} else {
    rawReleaseCertSha256.split(',')
        .map { entry -> entry.replace(":", "").filterNot { ch -> ch.isWhitespace() }.lowercase(Locale.US) }
        .also { list ->
            check(list.isNotEmpty() && list.all { sha256HexRegex.matches(it) }) {
                "Invalid RELEASE_CERT_SHA256 in version.properties: each entry must be a 64-hex SHA-256 certificate fingerprint"
            }
        }
        .joinToString(",")
}

val releaseKeystorePath = propOrEnv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword = propOrEnv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = propOrEnv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = propOrEnv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystorePath.isNotBlank() &&
    releaseKeystorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "com.walletwatcher.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.walletwatcher.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // Public open-source builds never bake an author API key into the APK.
        buildConfigField("String", "DEFAULT_AI_BASE_URL", quoted("https://api.deepseek.com"))
        buildConfigField("String", "DEFAULT_AI_MODEL", quoted("deepseek-chat"))
        buildConfigField("String", "DEFAULT_AI_API_KEY", quoted(""))
        buildConfigField("String", "GITHUB_REPO", quoted("la-l5/wallet-watcher-android"))
        buildConfigField("String", "TRUSTED_RELEASE_CERT_SHA256", quoted(trustedReleaseCertSha256))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
