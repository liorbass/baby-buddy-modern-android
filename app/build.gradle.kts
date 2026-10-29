import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Version details for Settings → About: the git tag/commit this was built from, and when it was built
// (SOURCE_DATE_EPOCH when set, as reproducible builds such as F-Droid's do; the date only, so the
// configuration cache is reused through the day)
val gitDescribe: String = runCatching {
  providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
  }.standardOutput.asText.get().trim()
}.getOrDefault("").ifBlank { "unknown" }

abstract class BuildDate : ValueSource<String, ValueSourceParameters.None> {
  override fun obtain(): String {
    val millis = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis()
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
  }
}
val buildDate: String = providers.of(BuildDate::class.java) {}.get()

android {
  namespace = "com.example"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "dev.liorbass.babybuddy"
    minSdk = 24
    targetSdk = 36
    // Bump both for each release (versionCode = MAJOR*10000 + MINOR*100 + PATCH), then tag vX.Y.Z;
    // the release workflow checks the tag matches. Kept as literals so F-Droid can read them.
    versionCode = 11200
    versionName = "1.12.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "GIT_DESCRIBE", "\"$gitDescribe\"")
    buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
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
      if (file("${rootDir}/debug.keystore").exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
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
  // Lists the app's languages (the values-* folders) for the per-app language setting on Android 13+;
  // the default strings in values/ are English (res/resources.properties)
  androidResources {
    generateLocaleConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // Robolectric 4.17 on SDK 36 uses JDK internals ("Failed to interact with raw FileDescriptor internals")
      all {
        it.jvmArgs(
          "--add-opens=java.base/java.io=ALL-UNNAMED",
          "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
        )
        // Show expected/actual of a failed assertion in the log (CI keeps no test reports)
        it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
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
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.okhttp.tls)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("buildApk") {
  group = "build"
  description = "Assembles the debug APK and prints its location."
  dependsOn("assembleDebug")
  val buildDir = layout.buildDirectory
  doLast {
    val apkFile = buildDir.file("outputs/apk/debug/app-debug.apk").get().asFile
    if (apkFile.exists()) {
      println("\n==================================================")
      println("  APK Generated Successfully!")
      println("  Path: ${apkFile.absolutePath}")
      println("==================================================\n")
    }
  }
}
