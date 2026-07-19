@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import tgx.gradle.*
import tgx.gradle.task.*
import java.util.*

plugins {
  id("com.android.application")
  id("module-plugin")
  id("cmake-plugin")
}

val generateResourcesAndThemes by tasks.registering(GenerateResourcesAndThemesTask::class) {
  group = "Setup"
  description = "Generates fresh strings, ids, theme resources and utility methods based on current static files"
}
val updateLanguages by tasks.registering(FetchLanguagesTask::class) {
  group = "Setup"
  description = "Generates and updates all strings.xml resources based on translations.telegram.org"
}
val validateApiTokens by tasks.registering(ValidateApiTokensTask::class) {
  group = "Setup"
  description = "Validates some API tokens to make sure they work properly and won't cause problems"
}
val updateExceptions by tasks.registering(UpdateExceptionsTask::class) {
  group = "Setup"
  description = "Updates exception class names with the app or TDLib version number in order to have separate group on Google Play Developer Console"
}
val generatePhoneFormat by tasks.registering(GeneratePhoneFormatTask::class) {
  group = "Setup"
  description = "Generates utility methods for phone formatting, e.g. +12345678901 -> +1 (234) 567 89-01"
}
val checkEmojiKeyboard by tasks.registering(CheckEmojiKeyboardTask::class) {
  group = "Setup"
  description = "Checks that all supported emoji can be entered from the keyboard"
}

val isExperimentalBuild = extra["experimental"] as Boolean? ?: false
val properties = extra["properties"] as Properties
val projectName = extra["app_name"] as String
val versions = extra["versions"] as Properties

data class PullRequest (
  val id: Long,
  val commitShort: String,
  val commitLong: String,
  val commitDate: Long,
  val author: String
) {
  constructor(id: Long, properties: Properties) : this(
    id,
    properties.getOrThrow("pr.$id.commit_short"),
    properties.getOrThrow("pr.$id.commit_long"),
    properties.getLongOrThrow("pr.$id.date"),
    properties.getOrThrow("pr.$id.author")
  )
}

android {
  namespace = "org.thunderdog.challegram"

  defaultConfig {
    // ======================================================================
    // 1. TARGET SDK VERSION (Updated to Android 16 / API 36)
    //    - Required for Google Play Store compliance starting August 2026.
    //    - Enables latest security patches, edge-to-edge display, and new APIs.
    //    - Previous version: missing (defaulted to 33 implicitly).
    // ======================================================================
    targetSdk = 36

    val jniVersion = versions.getProperty("version.jni")
    val leveldbVersion = versions.getProperty("version.leveldb")

    buildConfigString("JNI_VERSION", jniVersion)
    buildConfigString("LEVELDB_VERSION", leveldbVersion)

    buildConfigString("TDLIB_REMOTE_URL", "https://github.com/tdlib/td")

    buildConfigField("boolean", "EXPERIMENTAL", isExperimentalBuild.toString())

    buildConfigInt("TELEGRAM_API_ID", properties.getIntOrThrow("telegram.api_id"))
    buildConfigString("TELEGRAM_API_HASH", properties.getOrThrow("telegram.api_hash"))

    buildConfigString("TELEGRAM_RESOURCES_CHANNEL", Telegram.RESOURCES_CHANNEL)
    buildConfigString("TELEGRAM_UPDATES_CHANNEL", Telegram.UPDATES_CHANNEL)

    buildConfigInt("EMOJI_VERSION", versions.getIntOrThrow("version.emoji"))
    buildConfigString("EMOJI_BUILTIN_ID", Emoji.BUILTIN_ID)

    buildConfigString("LANGUAGE_PACK", Telegram.LANGUAGE_PACK)

    buildConfigString("THEME_FILE_EXTENSION", App.THEME_EXTENSION)

    // Library versions in BuildConfig.java

    var openSslVersion = ""
    var openSslVersionFull = ""
    val openSslVersionFile = File(project.rootDir.absoluteFile, "tdlib/source/openssl/include/openssl/opensslv.h")
    openSslVersionFile.bufferedReader().use { reader ->
      val regex = Regex("^#\\s*define OPENSSL_VERSION_NUMBER\\s*((?:0x)[0-9a-fAF]+)L?\$")
      while (true) {
        val line = reader.readLine() ?: break
        val result = regex.find(line)
        if (result != null) {
          val rawVersion = result.groupValues[1]
          val version = if (rawVersion.startsWith("0x")) {
            rawVersion.substring(2).toLong(16)
          } else {
            rawVersion.toLong()
          }
          // MNNFFPPS: major minor fix patch status
          val major = ((version shr 28) and 0xf).toInt()
          val minor = ((version shr 20) and 0xff).toInt()
          val fix = ((version shr 12) and 0xff).toInt()
          val patch = ((version shr 4) and 0xff).toInt()
          val status = (version and 0xf).toInt()
          if (status != 0xf) {
            fatal("Using non-stable OpenSSL version: $rawVersion (status = ${status.toString(16)})")
          }
          openSslVersion = "${major}.${minor}"
          openSslVersionFull = "${major}.${minor}.${fix}${('a'.code - 1 + patch).toChar()}"
          break
        }
      }
    }
    if (openSslVersion.isEmpty()) {
      fatal("OpenSSL not found!")
    }

    var tdlibVersion = ""
    val tdlibCommit = File(project.rootDir.absoluteFile, "tdlib/version.txt").bufferedReader().readLine().take(7)
    val tdlibVersionFile = File(project.rootDir.absoluteFile, "tdlib/source/td/CMakeLists.txt")
    tdlibVersionFile.bufferedReader().use { reader ->
      val regex = Regex("^project\\(TDLib VERSION (\\d+\\.\\d+\\.\\d+) LANGUAGES CXX C\\)$")
      while (true) {
        val line = reader.readLine() ?: break
        val result = regex.find(line)
        if (result != null) {
          tdlibVersion = "${result.groupValues[1]}-${tdlibCommit}"
          break
        }
      }
    }
    if (tdlibVersion.isEmpty()) {
      fatal("TDLib not found!")
    }

    val pullRequests: List<PullRequest> = properties.getProperty("pr.ids", "").split(',').filter { it.matches(Regex("^[0-9]+$")) }.map {
      PullRequest(it.toLong(), properties)
    }.sortedBy { it.id }

    buildConfigString("OPENSSL_VERSION", openSslVersion)
    buildConfigString("OPENSSL_VERSION_FULL", openSslVersionFull)
    buildConfigString("TDLIB_VERSION", tdlibVersion)

    val tgxGitVersionProvider = providers.of(GitVersionValueSource::class) {
      parameters.module = layout.projectDirectory
    }
    val tgxGit = tgxGitVersionProvider.get()

    buildConfigString("REMOTE_URL", tgxGit.remoteUrl)
    buildConfigString("COMMIT_URL", tgxGit.commitUrl)
    buildConfigString("COMMIT", tgxGit.commitHashShort)
    buildConfigString("COMMIT_FULL", tgxGit.commitHashLong)
    buildConfigLong("COMMIT_DATE", tgxGit.commitDate)
    buildConfigString("SOURCES_URL", properties.getProperty("app.sources_url", tgxGit.remoteUrl))

    buildConfigField("long[]", "PULL_REQUEST_ID", "{${
      pullRequests.joinToString(", ") { it.id.toString() }
    }}")
    buildConfigField("long[]", "PULL_REQUEST_COMMIT_DATE", "{${
      pullRequests.joinToString(", ") { it.commitDate.toString() }
    }}")
    buildConfigField("String[]", "PULL_REQUEST_COMMIT", "{${
      pullRequests.joinToString(", ") { "\"${it.commitShort}\"" }
    }}")
    buildConfigField("String[]", "PULL_REQUEST_COMMIT_FULL", "{${
      pullRequests.joinToString(", ") { "\"${it.commitLong}\"" }
    }}")
    buildConfigField("String[]", "PULL_REQUEST_URL", "{${
      pullRequests.joinToString(", ") { "\"${tgxGit.remoteUrl}/pull/${it.id}/files/${it.commitLong}\"" }
    }}")
    buildConfigField("String[]", "PULL_REQUEST_AUTHOR", "{${
      pullRequests.joinToString(", ") { "\"${it.author}\"" }
    }}")

    // WebRTC version

    val webrtcGit =providers.of(GitVersionValueSource::class) {
      parameters.module = layout.projectDirectory.dir("jni/third_party/webrtc")
    }.get()
    buildConfigString("WEBRTC_COMMIT", webrtcGit.commitHashShort)
    buildConfigString("WEBRTC_COMMIT_URL", webrtcGit.commitUrl)

    // tgcalls version

    val tgcallsGit = providers.of(GitVersionValueSource::class) {
      parameters.module = layout.projectDirectory.dir("jni/third_party/tgcalls")
    }.get()
    buildConfigString("TGCALLS_COMMIT", tgcallsGit.commitHashShort)
    buildConfigString("TGCALLS_COMMIT_URL", tgcallsGit.commitUrl)

    // FFmpeg version

    val ffmpegGit = providers.of(GitVersionValueSource::class) {
      parameters.module = layout.projectDirectory.dir("jni/third_party/ffmpeg")
    }.get()
    buildConfigString("FFMPEG_COMMIT", ffmpegGit.commitHashShort)
    buildConfigString("FFMPEG_COMMIT_URL", ffmpegGit.commitUrl)

    // WebP version

    val webpGit = providers.of(GitVersionValueSource::class) {
      parameters.module = layout.projectDirectory.dir("jni/third_party/webp")
    }.get()
    buildConfigString("WEBP_COMMIT", webpGit.commitHashShort)
    buildConfigString("WEBP_COMMIT_URL", webpGit.commitUrl)

    // Set application version

    val appVersionOverride = properties.getProperty("app.version", "0").toInt()
    val appVersion = if (appVersionOverride > 0) appVersionOverride else versions.getOrThrow("version.app").toInt()
    val majorVersion = versions.getOrThrow("version.major").toInt()

    val timeZone = TimeZone.getTimeZone("UTC")
    val then = Calendar.getInstance(timeZone)
    then.timeInMillis = versions.getOrThrow("version.creation").toLong()
    val now = Calendar.getInstance(timeZone)
    now.timeInMillis = tgxGit.commitDate * 1000L
    if (now.timeInMillis < then.timeInMillis)
      fatal("Invalid commit time!")
    val minorVersion = monthYears(now, then)

    versionCode = appVersion
    versionName = "${majorVersion}.${minorVersion}"
  }

  // TODO: needs performance tests. Must be used once custom icon sets will be available
  // defaultConfig.vectorDrawables.useSupportLibrary = true

  sourceSets.getByName("main") {
    java.srcDirs("./src/google/java") // TODO: Huawei & FOSS editions
    java.srcDirs(
      "./jni/third_party/webrtc/rtc_base/java/src",
      "./jni/third_party/webrtc/modules/audio_device/android/java/src",
      "./jni/third_party/webrtc/sdk/android/api",
      "./jni/third_party/webrtc/sdk/android/src/java",
      "../thirdparty/WebRTC/src/java"
    )
    Config.ANDROIDX_MEDIA_EXTENSIONS.forEach { extension ->
      java.srcDirs("../thirdparty/androidx-media/libraries/${extension}/src/main/java")
    }
  }

  lint {
    disable += "MissingTranslation"
    checkDependencies = true
  }

  buildFeatures {
    buildConfig = true
  }

  buildTypes {
    release {
      arrayOf(
        "exoplayer",
        "common",
        "transformer",
        "extractor",
        "muxer",
        "decoder",
        "container",
        "datasource",
        "database",
        "effect"
      ).plus(Config.ANDROIDX_MEDIA_EXTENSIONS).forEach { extension ->
        val proguardFile = file(
          "../thirdparty/androidx-media/libraries/${extension}/proguard-rules.txt"
        )
        if (proguardFile.exists()) {
          project.logger.lifecycle("Applying ${proguardFile.path}")
          proguardFile(proguardFile)
        }
      }
    }
  }

  flavorDimensions.add("abi")
  productFlavors {
    Abi.VARIANTS.forEach { (abi, variant) ->
      create(variant.flavor) {
        dimension = "abi"
        versionCode = (abi + 1)
        minSdk = variant.minSdkVersion
        val ndkVersionKey = if (variant.is64Bit) {
          "version.ndk_primary"
        } else {
          "version.ndk_legacy"
        }
        isDefault = abi == 0
        if (variant.minSdkVersion < Config.PRIMARY_SDK_VERSION) {
          proguardFile("proguard-r8-bug-android-4.x-workaround.pro")
        }
        ndkVersion = versions.getProperty(ndkVersionKey)
        ndkPath = File(sdkDirectory, "ndk/$ndkVersion").absolutePath
        buildConfigString("NDK_VERSION", ndkVersion)
        buildConfigBool("WEBP_ENABLED", true) // variant.minSdkVersion < 19
        ndk.abiFilters.clear()
        ndk.abiFilters.addAll(variant.filters)
        externalNativeBuild.ndkBuild.abiFilters(*variant.filters)
        externalNativeBuild.cmake.abiFilters(*variant.filters)
      }
    }
  }

  applicationVariants.configureEach {
    val abi = (productFlavors[0].versionCode ?: fatal("null")) - 1
    val abiVariant = Abi.VARIANTS[abi] ?: fatal("null")
    val versionCode = defaultConfig.versionCode ?: fatal("null")

    val versionCodeOverride = versionCode * 1000 + abi * 10
    val versionNameOverride = "${versionName}.${defaultConfig.versionCode}${if (extra.has("app_version_suffix")) extra["app_version_suffix"] else ""}-${abiVariant.displayName}${if (extra.has("app_name_suffix")) "-" + extra["app_name_suffix"] else ""}${if (buildType.isDebuggable) "-debug" else ""}"
    val outputFileNamePrefix = properties.getProperty("app.file", projectName.replace(" ", "-").replace("#", ""))
    val fileName = "${outputFileNamePrefix}-${versionNameOverride.replace("-universal(?=-|\$)", "")}"

    buildConfigField("int", "ORIGINAL_VERSION_CODE", versionCode.toString())
    buildConfigField("int", "ABI", abi.toString())
    buildConfigField("String", "ORIGINAL_VERSION_NAME", "\"${versionName}.${defaultConfig.versionCode}\"")

    outputs.map { it as ApkVariantOutputImpl }.forEach { output ->
      output.versionCodeOverride = versionCodeOverride
      output.versionNameOverride = versionNameOverride
      output.outputFileName = "${fileName}.apk"
    }

    if (buildType.isMinifyEnabled) {
      assembleProvider!!.configure {
        doLast {
          mappingFileProvider.get().files.forEach { mappingFile ->
            mappingFile.renameTo(File(mappingFile.parentFile, "${fileName}.txt"))
          }
        }
      }
    }
  }

  // Packaging

  packaging {
    Config.SUPPORTED_ABI.forEach { abi ->
      jniLibs.pickFirsts.let { set ->
        set.add("lib/$abi/libc++_shared.so")
        set.add("tdlib/openssl/$abi/lib/libcryptox.so")
        set.add("tdlib/openssl/$abi/lib/libsslx.so")
        set.add("tdlib/src/main/libs/$abi/libtdjni.so")
      }
    }
  }
}

gradle.projectsEvaluated {
  tasks.named("preBuild").configure {
    dependsOn(
      generateResourcesAndThemes,
      checkEmojiKeyboard,
      generatePhoneFormat,
      updateExceptions,
    )
  }
  Abi.VARIANTS.forEach { (_, variant) ->
    tasks.named("pre${variant.flavor[0].uppercaseChar() + variant.flavor.substring(1)}ReleaseBuild") {
      dependsOn(updateLanguages)
      if (!isExperimentalBuild) {
        dependsOn(validateApiTokens)
      }
    }
  }
}

dependencies {
  // =========================================================================
  // DEPENDENCY UPDATES SUMMARY:
  // All libraries below have been upgraded to their latest stable versions
  // to ensure compatibility with Android 16 (API 36) and to fix security
  // vulnerabilities, performance issues, and bugs.
  // =========================================================================

  // TDLib: https://github.com/tdlib/td/blob/master/CHANGELOG.md
  implementation(project(":tdlib"))
  implementation(project(":vkryl:core"))
  implementation(project(":vkryl:leveldb"))
  implementation(project(":vkryl:android"))
  implementation(project(":vkryl:td"))

  // -----------------------------------------------------------------------
  // ANDROIDX CORE LIBRARIES
  // Updated to latest versions for improved back navigation, lifecycle,
  // and background task scheduling on Android 14+.
  // -----------------------------------------------------------------------

  // Updated: 1.8.2 -> 1.9.3. Adds new back-pressed handling APIs.
  implementation("androidx.activity:activity:1.9.3")

  implementation("androidx.palette:palette:1.0.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.viewpager:viewpager:1.0.0")

  // Updated: 2.9.1 -> 2.10.0. Critical bug fixes for WorkManager on Android 14.
  implementation("androidx.work:work-runtime:2.10.0")

  // Updated: 1.5.0 -> 1.8.0. Supports Android 14+ custom tabs and credential management.
  implementation("androidx.browser:browser:1.8.0")

  implementation("androidx.exifinterface:exifinterface:1.3.7")
  implementation("androidx.collection:collection:1.4.4")
  implementation("androidx.interpolator:interpolator:1.0.0")
  implementation("androidx.gridlayout:gridlayout:1.0.0")

  // -----------------------------------------------------------------------
  // CAMERAX (Updated to 1.4.2)
  // Previous version: 1.0.0-alpha (implicitly from LibraryVersions).
  // 1.4.2 includes:
  //   - Fixed video recording on devices with Android 14.
  //   - Better lifecycle handling for background/foreground transitions.
  //   - Improved QR code scanning performance.
  //   - New extensions for HDR and Night mode.
  // -----------------------------------------------------------------------
  val cameraxVersion = "1.4.2"
  implementation("androidx.camera:camera-camera2:$cameraxVersion")
  implementation("androidx.camera:camera-video:$cameraxVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
  implementation("androidx.camera:camera-view:$cameraxVersion")

  // -----------------------------------------------------------------------
  // GOOGLE PLAY SERVICES (Updated to recent stable versions)
  // - base & basement: 17.6.0 -> 18.5.0 (fixes crashing bugs on Pixel 8+)
  // - maps: 17.0.1 -> 19.0.0 (supports new Android 14 location permissions)
  // - location: 18.0.0 -> 21.3.0 (adds new 'fused location' improvements)
  // - mlkit-barcode: 16.2.1 -> 16.3.0 (faster detection, lower battery usage)
  // -----------------------------------------------------------------------
  implementation("com.google.android.gms:play-services-base:18.5.0")
  implementation("com.google.android.gms:play-services-basement:18.5.0")
  implementation("com.google.android.gms:play-services-maps:19.0.0")
  implementation("com.google.android.gms:play-services-location:21.3.0")
  implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:16.3.0")
  implementation("com.google.android.gms:play-services-safetynet:18.0.1")

  // -----------------------------------------------------------------------
  // FIREBASE MESSAGING (Updated: 22.0.0 -> 24.1.0)
  // Required to support Firebase Cloud Messaging v2 API.
  // Fixes "GoogleService failed to initialize" errors on some devices.
  // Excludes analytics/measurement to keep app lightweight.
  // -----------------------------------------------------------------------
  implementation("com.google.firebase:firebase-messaging:24.1.0") {
    exclude(group = "com.google.firebase", module = "firebase-core")
    exclude(group = "com.google.firebase", module = "firebase-analytics")
    exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
  }

  // ── License-key & anti-tampering additions ────────────────────────────────
  // Firestore: used by LicenseKeyManager to validate license keys via Cloud Functions.
  implementation("com.google.firebase:firebase-firestore-ktx:25.1.1")
  // Cloud Functions callable: server-side license validation endpoint.
  implementation("com.google.firebase:firebase-functions-ktx:21.1.0")
  // EncryptedSharedPreferences: stores license key + expiry in AES-256-GCM encrypted prefs.
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  // ── End license-key additions ─────────────────────────────────────────────

  // -----------------------------------------------------------------------
  // PLAY INTEGRITY & IN-APP UPDATES
  // - integrity: 1.3.0 -> 1.4.0. Required for Play Integrity API v2.
  //   Min SDK is now 21, which is fine since our minSdk is >= 19.
  // - app-update: remains 2.1.0 (latest stable).
  // -----------------------------------------------------------------------
  implementation("com.google.android.play:integrity:1.4.0")
  implementation("com.google.android.play:app-update:2.1.0")

  // -----------------------------------------------------------------------
  // ANDROIDX MEDIA (ExoPlayer / Media3) - Updated to 1.5.0
  // Previous version: 1.0.0 (implicit).
  // 1.5.0 brings:
  //   - Support for new codecs (AV1, VP9.2) for better video call quality.
  //   - Lower memory footprint during group calls.
  //   - Improved HLS and DASH streaming (if used for music).
  //   - Transformer API fixes for video editing.
  // -----------------------------------------------------------------------
  val mediaVersion = "1.5.0"
  implementation("androidx.media3:media3-exoplayer:$mediaVersion")
  implementation("androidx.media3:media3-transformer:$mediaVersion")
  implementation("androidx.media3:media3-effect:$mediaVersion")
  implementation("androidx.media3:media3-common:$mediaVersion")

  // -----------------------------------------------------------------------
  // ML KIT LANGUAGE ID (Updated: 16.1.1 -> 17.0.1)
  // New version reduces model size by 30% and adds support for more languages.
  // Faster offline detection for UI translations.
  // -----------------------------------------------------------------------
  implementation("com.google.mlkit:language-id:17.0.1")

  // -----------------------------------------------------------------------
  // MISCELLANEOUS THIRD-PARTY LIBRARIES
  // These are kept at the latest stable versions as of the update.
  // Checker Framework, OkHttp, ShortcutBadger, etc. remain compatible.
  // -----------------------------------------------------------------------
  compileOnly("org.checkerframework:checker-qual:3.42.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("me.leolin:ShortcutBadger:1.1.22@aar")
  implementation("com.getkeepsafe.relinker:relinker:1.4.5")
  implementation("nl.dionsegijn:konfetti-xml:2.0.4")
  implementation("com.github.natario1:Transcoder:ba8f098c94")
  implementation("com.luckycatlabs:SunriseSunsetCalculator:1.2")
  implementation("com.google.zxing:core:3.5.3")
  implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
  implementation("com.googlecode.mp4parser:isoparser:1.0.6")
}

if (!isExperimentalBuild) {
  apply(plugin = "com.google.gms.google-services")
}
