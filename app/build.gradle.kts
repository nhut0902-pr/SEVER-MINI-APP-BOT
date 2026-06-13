import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  val extVersionName = System.getenv("VERSION_NAME") ?: "1.0.9"
  val extVersionCodeStr = System.getenv("VERSION_CODE") ?: "9"
  val extVersionCode = extVersionCodeStr.toIntOrNull() ?: 9

  defaultConfig {
    applicationId = "com.aistudio.miniserver.pwyqzx"
    minSdk = 24
    targetSdk = 36
    versionCode = extVersionCode
    versionName = extVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Auto-generate debug.keystore from standard base64 if not already present,
  // guaranteeing identical signatures across AI Studio, Gradle, and GitHub Actions
  val debugKeystorePath = "${rootDir}/debug.keystore"
  if (!file(debugKeystorePath).exists()) {
    try {
      val b64 = "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"
      val bytes = Base64.getDecoder().decode(b64)
      file(debugKeystorePath).writeBytes(bytes)
      println("Successfully generated debug.keystore from embedded fallback base64")
    } catch (e: Exception) {
      println("Error generating debug.keystore: ${e.message}")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_FILE") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("KEYSTORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    getByName("debug") {
      val keystorePath = "${rootDir}/debug.keystore"
      if (file(keystorePath).exists()) {
        storeFile = file(keystorePath)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
