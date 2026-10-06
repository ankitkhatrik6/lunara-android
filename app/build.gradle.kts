import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// Signing credentials are resolved in this order:
//   1. Environment variables (used by CI)
//   2. local.properties (git-ignored, for local release builds)
//   3. The keystore committed at the repository root
// The same stable key is always used so Android (and Play Protect) accept
// updates over previously installed builds.
val signingProps = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String, fallback: String): String =
  System.getenv(env)?.takeIf { it.isNotBlank() }
    ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: fallback

android {
  namespace = "com.lunara.music"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.lunara.music"
    minSdk = 24
    targetSdk = 36
    // Increment versionCode for every published update. A stable applicationId,
    // a stable signing key and a strictly increasing versionCode together are
    // what allow an APK to be installed over a previous build.
    versionCode = 8
    versionName = "2.2.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      storeFile = file(signingValue("KEYSTORE_PATH", "lunara.storeFile", "${rootDir}/lunara-upload-key.jks"))
      storePassword = signingValue("STORE_PASSWORD", "lunara.storePassword", "lunara-upload")
      keyAlias = signingValue("KEY_ALIAS", "lunara.keyAlias", "lunara")
      keyPassword = signingValue("KEY_PASSWORD", "lunara.keyPassword", "lunara-upload")
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      isMinifyEnabled = false
      // Sign debug builds with the same stable key as release. Without this,
      // installing a release build over a debug build (or the reverse) fails
      // with INSTALL_FAILED_UPDATE_INCOMPATIBLE ("App not installed"), because
      // the signing certificates would differ even though the package matches.
      signingConfig = signingConfigs.getByName("release")
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
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)

  // LunaraExtractor: the stream-extraction engine (BotGuard PO-token minting,
  // client rotation, stream resolution). This is what makes online playback work.
  implementation(project(":extractor"))

  // Audio engine (Media3 / ExoPlayer) + OkHttp-backed streaming data source.
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.datasource.okhttp)

  // In-app YouTube InnerTube stream extraction and lyrics. No external
  // extractor dependency is used.
  implementation(libs.okhttp)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  "ksp"(libs.androidx.room.compiler)
}
