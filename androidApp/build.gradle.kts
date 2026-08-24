import java.util.Properties

val isFullBuild: Boolean =
    try {
        extra["isFullBuild"] == "true"
    } catch (e: Exception) {
        false
    }

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
if (hasReleaseSigning) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.sentry.gradle)
    alias(libs.plugins.compose.compiler)
}

android {
    val abis = arrayOf("armeabi-v7a", "arm64-v8a", "x86_64")

    namespace = "com.nothingplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nothing.lyricwidget"
        minSdk = 26
        targetSdk = 36
        versionCode =
            libs.versions.version.code
                .get()
                .toInt()
        versionName =
            libs.versions.version.name
                .get()
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true

        @Suppress("UnstableApiUsage")
        androidResources {
            localeFilters +=
                listOf(
                    "en",
                    "vi",
                    "it",
                    "de",
                    "ru",
                    "tr",
                    "fi",
                    "pl",
                    "pt",
                    "fr",
                    "es",
                    "zh-rCN",
                    "id",
                    "in",
                    "ar",
                    "ja",
                    "zh-rTW",
                    "uk",
                    "iw",
                    "az",
                    "hi",
                    "th",
                    "nl",
                    "ko",
                    "ca",
                    "fa",
                    "bg",
                )
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("x86_64")
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            splits {
                abi {
                    isEnable = true
                    reset()
                    isUniversalApk = true
                    include(*abis)
                }
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // enable view binding
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.excludes +=
            listOf(
                "META-INF/META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/asm-license.txt",
                "META-INF/notice",
                "META-INF/*.kotlin_module",
            )
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
    val debugImplementation = "debugImplementation"
    debugImplementation(libs.ui.tooling)
    implementation(libs.activity.compose)

    // Material3 (androidx.compose.material3 classes for androidApp-only UI)
    implementation(libs.compose.material3)

    // Custom Activity On Crash
    implementation(libs.customactivityoncrash)

    // Easy Permissions
    implementation(libs.easypermissions)

    // Legacy Support
    implementation(libs.legacy.support.v4)
    // Coroutines
    implementation(libs.coroutines.android)

    // Nothing Glyph Matrix SDK (optional - only works on Nothing Phone devices)
    implementation("com.nothing:glyph-matrix-sdk:2.0@aar")

    // Media3 ExoPlayer + ui-compose (PlayerSurface) for the AOD lyrics canvas video
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.compose)

    implementation(projects.composeApp)
    implementation(projects.data)

    if (isFullBuild) {
        implementation(projects.crashlytics)
    } else {
        implementation(projects.crashlyticsEmpty)
    }
}

sentry {
    org.set("nothingplayer")
    projectName.set("android")
    ignoredFlavors.set(setOf("foss"))
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation.enabled = false
    if (isFullBuild) {
        val token =
            try {
                println("Full build detected, enabling Sentry Auth Token")
                val properties = Properties()
                properties.load(rootProject.file("local.properties").inputStream())
                properties.getProperty("SENTRY_AUTH_TOKEN")
            } catch (e: Exception) {
                println("Failed to load SENTRY_AUTH_TOKEN from local.properties: ${e.message}")
                null
            }
        authToken.set(token ?: "")
        includeProguardMapping.set(true)
        autoUploadProguardMapping.set(true)
    } else {
        includeProguardMapping.set(false)
        autoUploadProguardMapping.set(false)
        uploadNativeSymbols.set(false)
        includeDependenciesReport.set(false)
        includeSourceContext.set(false)
        includeNativeSources.set(false)
    }
    telemetry.set(false)
}

if (!isFullBuild) {
    abstract class CleanSentryMetaTask : DefaultTask() {
        @get:InputFiles
        abstract val assetDirectories: ConfigurableFileCollection

        @get:Internal
        abstract val buildDirectory: DirectoryProperty

        @TaskAction
        fun execute() {
            assetDirectories.forEach { assetDir ->
                val sentryFile = File(assetDir, "sentry-debug-meta.properties")
                if (sentryFile.exists()) {
                    sentryFile.delete()
                    println("Deleted: ${sentryFile.absolutePath}")
                }
            }

            val dirName = "release/mergeReleaseAssets"
            val injectDirName = "release/injectSentryDebugMetaPropertiesIntoAssetsRelease"
            println("Cleaning Sentry meta files in build directories")
            println("Build directory: ${buildDirectory.asFile.get().absolutePath}")

            val buildAssetsDir = File(buildDirectory.asFile.get(), "intermediates/assets/$dirName")
            println("Checking directory buildAssetsDir: ${buildAssetsDir.absolutePath}")
            val sentryFile = File(buildAssetsDir, "sentry-debug-meta.properties")
            if (sentryFile.exists()) {
                sentryFile.delete()
                println("Deleted: ${sentryFile.absolutePath}")
            }

            val injectBuildAssetsDir = File(buildDirectory.asFile.get(), "intermediates/assets/$injectDirName")
            println("Checking directory injectBuildAssetsDir: ${injectBuildAssetsDir.absolutePath}")
            val injectSentryFile = File(injectBuildAssetsDir, "sentry-debug-meta.properties")
            if (injectSentryFile.exists()) {
                injectSentryFile.delete()
                println("Deleted: ${injectSentryFile.absolutePath}")
                val sentryFile = File(injectBuildAssetsDir, "sentry-debug-meta.properties")
                sentryFile.writeText("")
                println("âœ“ Overwritten: ${sentryFile.absolutePath}")
            }
        }
    }

    tasks.whenTaskAdded {
        if (name.contains("injectSentryDebugMetaPropertiesIntoAssetsRelease")) {
            val cleanSentryMetaTaskName = "cleanSentryMetaForRelease"
            val cleanSentryMetaTask =
                tasks.register<CleanSentryMetaTask>(cleanSentryMetaTaskName) {
                    assetDirectories.from(android.sourceSets.flatMap { it.assets.directories })
                    buildDirectory.set(layout.buildDirectory)
                }
            tasks.named(name).configure {
                finalizedBy(cleanSentryMetaTask)
            }
        }
    }
}