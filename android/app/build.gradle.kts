import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional local overrides (never committed). CI passes these via env vars.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(name: String, fallback: String): String =
    (System.getenv(name) ?: localProps.getProperty(name) ?: fallback)

android {
    namespace = "com.sara.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sara.assistant"
        minSdk = 28
        targetSdk = 36
        versionCode = cfg("SARA_VERSION_CODE", "1").toInt()
        versionName = cfg("SARA_VERSION_NAME", "0.1.0")

        // Endpoints are configurable at runtime from Settings; these are just first-run defaults.
        buildConfigField("String", "DEFAULT_BACKEND_WS", "\"${cfg("SARA_BACKEND_WS", "ws://10.0.2.2:8000/ws/session")}\"")
        buildConfigField("String", "DEFAULT_LIVE_MODEL", "\"gemini-3.8-live\"")
        buildConfigField("String", "DEFAULT_LIVE_MODEL_FALLBACK", "\"gemini-3.1-flash-live-preview\"")
    }

    signingConfigs {
        create("release") {
            val ksPath = cfg("SARA_KEYSTORE_PATH", rootProject.file("sara-release.jks").absolutePath)
            val ks = file(ksPath)
            if (ks.exists()) {
                storeFile = ks
                storePassword = cfg("SARA_KEYSTORE_PASSWORD", "sara-release")
                keyAlias = cfg("SARA_KEY_ALIAS", "sara")
                keyPassword = cfg("SARA_KEY_PASSWORD", "sara-release")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fall back to debug signing so CI can always produce an installable APK,
            // unless a real release keystore is supplied (see README).
            signingConfig = if (file(cfg("SARA_KEYSTORE_PATH", rootProject.file("sara-release.jks").absolutePath)).exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
