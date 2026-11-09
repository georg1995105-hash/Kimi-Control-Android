plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "pro.veor.kimicontrol"
  compileSdk = 35
  defaultConfig {
    applicationId = "pro.veor.kimicontrol"
    minSdk = 26
    targetSdk = 35
    versionCode = 1000 + (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0)
    versionName = "0.3.0"
  }
  val releaseKeystore = System.getenv("KIMI_KEYSTORE_PATH")
  if (!releaseKeystore.isNullOrBlank()) {
    signingConfigs {
      create("stable") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("KIMI_KEYSTORE_PASSWORD")
        keyAlias = "kimi-control"
        keyPassword = System.getenv("KIMI_KEY_PASSWORD")
      }
    }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      if (!releaseKeystore.isNullOrBlank()) {
        signingConfig = signingConfigs.getByName("stable")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
}
dependencies {
  val bom = platform("androidx.compose:compose-bom:2025.04.01")
  implementation(bom)
  androidTestImplementation(bom)
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
