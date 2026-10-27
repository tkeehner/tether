plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Release signing is bring-your-own-key. Nothing secret lives in this repo. Provide these as environment
// variables (or in ~/.gradle/gradle.properties, never committed) to get a signed release APK:
//   TETHER_KEYSTORE=/path/to/your.jks  TETHER_KEYSTORE_PASSWORD=...  TETHER_KEY_ALIAS=...  TETHER_KEY_PASSWORD=...
// Without them, assembleRelease produces an unsigned APK.
fun secret(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
val releaseKeystore = secret("TETHER_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
android {
    namespace = "com.tyler.primelink"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tyler.primelink"
        minSdk = 33 // Android 13+
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = secret("TETHER_KEYSTORE_PASSWORD")
            keyAlias = secret("TETHER_KEY_ALIAS")
            keyPassword = secret("TETHER_KEY_PASSWORD") ?: secret("TETHER_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true
        unitTests.all { t -> listOf("PLINK_PY", "PLINK_PC").forEach { k -> System.getenv(k)?.let { t.environment(k, it) } } } }
    lint { abortOnError = true; checkReleaseBuilds = true; disable += listOf("MissingTranslation") }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.5.0")
}
