import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.glancefiles.vxqpmr"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
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
  splits {
    abi {
      isEnable = providers.gradleProperty("enableAbiSplits").orNull == "true"
      reset()
      val requestedAbi = providers.gradleProperty("targetAbi").orNull
      if (!requestedAbi.isNullOrBlank()) {
        include(requestedAbi)
      } else {
        include("arm64-v8a", "x86_64")
      }
      isUniversalApk = false
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

val rootApkDir = rootProject.layout.projectDirectory.dir("apk").asFile
val buildApkOutputsDir = layout.buildDirectory.dir("outputs/apk")

val copyApksToRootFolder by tasks.registering {
  description = "Copies only the 2 standalone debug APKs (arm64-v8a and x86_64) into the root /apk directory"
  val targetDir = rootApkDir
  val assetsDir = rootProject.layout.projectDirectory.dir("public/assets/aistudio").asFile
  val sourceDirProvider = buildApkOutputsDir
  doLast {
    if (!targetDir.exists()) {
      targetDir.mkdirs()
    }
    // Clean up any duplicate app-*.apk or universal APK files so only the 2 ABI APKs remain
    targetDir.listFiles()?.forEach { existingFile ->
      if (existingFile.isFile &&
          existingFile.name != "GlanceFiles-arm64-v8a-debug.apk" &&
          existingFile.name != "GlanceFiles-x86_64-debug.apk") {
        existingFile.delete()
      }
    }
    // Ensure personal QR screenshots are deleted from public/assets/aistudio
    listOf(
      "Screenshot_20261112-204137_Moto_App_Launcher_ff19b288.png",
      "Screenshot_20261112-212857_Moto_App_Launcher_bdcaf432.png"
    ).forEach { fileName ->
      val f = File(assetsDir, fileName)
      if (f.exists()) {
        f.delete()
      }
    }
    val outputsDir = sourceDirProvider.get().asFile
    if (outputsDir.exists()) {
      outputsDir.walkTopDown()
        .filter { it.isFile && it.extension == "apk" }
        .forEach { apkFile ->
          when (apkFile.name) {
            "app-arm64-v8a-debug.apk" -> {
              apkFile.copyTo(File(targetDir, "GlanceFiles-arm64-v8a-debug.apk"), overwrite = true)
            }
            "app-x86_64-debug.apk" -> {
              apkFile.copyTo(File(targetDir, "GlanceFiles-x86_64-debug.apk"), overwrite = true)
            }
          }
        }
    }
  }
}

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("package") }.configureEach {
  finalizedBy(copyApksToRootFolder)
}

