import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secretOrDefault(name: String, fallback: String = ""): String {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: fallback
    return raw.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.walletwatcher.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.walletwatcher.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"

        buildConfigField("String", "DEFAULT_AI_BASE_URL", "\"${secretOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com")}\"")
        buildConfigField("String", "DEFAULT_AI_MODEL", "\"${secretOrDefault("DEEPSEEK_MODEL", "deepseek-chat")}\"")
        buildConfigField("String", "DEFAULT_AI_API_KEY", "\"${secretOrDefault("DEEPSEEK_API_KEY", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        unitTests.isReturnDefaultValues = true
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
}
