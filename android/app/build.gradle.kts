plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Go core, built by mobile/build-android.sh: pin, probe, race, DoH and the
// tunnel, for every phone CPU. Not in the repo - any checkout rebuilds it.
val mirageAar = file("libs/mirage.aar")

// The server installer the tunnel pane hands out as one pasteable line. It
// lives in tunnel/ for the desktop; copied in here at build time rather than
// committed twice.
val serverInstaller = rootProject.file("../tunnel/install-server.sh")
val generatedAssets = layout.buildDirectory.dir("generated/mirage-assets")

val copyInstaller by tasks.registering(Copy::class) {
    from(serverInstaller)
    into(generatedAssets)
}

android {
    namespace = "app.mirage"
    compileSdk = 36
    // The same NDK mobile/build-android.sh builds the core with. The app
    // compiles no C of its own; AGP only insists that one be named.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        // Mirage's own id since the rename, not the old app's: a new
        // install beside the old app, with nothing carried over from it.
        applicationId = "app.mirage"
        // 24: VpnService.Builder.setMetered wants it.
        minSdk = 24
        targetSdk = 36
        // Above the Flutter build's 1, or Android refuses the upgrade.
        versionCode = 7
        versionName = "0.3.4"
    }

    // Android identifies an app by the key that signed it, so a release has
    // to be signed by the same key every time. Same variables as the Flutter
    // build; without them a local release build falls back to the debug key
    // - which is fine on a desk and never fine for a release
    // anybody downloads. CI's release path sets MIRAGE_REQUIRE_SIGNING, and
    // then a missing or unreadable key stops the build instead.
    val keystore = System.getenv("MIRAGE_KEYSTORE")
    val keystorePassword = System.getenv("MIRAGE_KEYSTORE_PASSWORD")
    val signed = !keystore.isNullOrEmpty() && !keystorePassword.isNullOrEmpty() &&
        file(keystore).exists()
    if (System.getenv("MIRAGE_REQUIRE_SIGNING") == "true" && !signed) {
        throw GradleException(
            "MIRAGE_REQUIRE_SIGNING is set but there is no usable release key: set MIRAGE_KEYSTORE " +
                "to an existing keystore and MIRAGE_KEYSTORE_PASSWORD to its password.")
    }

    signingConfigs {
        if (signed) {
            create("release") {
                storeFile = file(keystore!!)
                storePassword = keystorePassword
                // "relay" is the alias inside the existing release keystore,
                // made before the app was called Mirage; it is the key's name.
                keyAlias = System.getenv("MIRAGE_KEY_ALIAS") ?: "relay"
                keyPassword = System.getenv("MIRAGE_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 matters to how this feels, not only to its size: Compose in a
            // debuggable, unoptimised build recomposes several times slower,
            // and that was the stutter on every connect.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signed) "release" else "debug")
        }
        // A release-speed build that installs over the debug one: the same
        // application id and the same debug key, so it keeps that install's
        // accounts and configs. For feeling how the app really runs on a
        // phone that has been set up with the debug build.
        create("fast") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // One APK per architecture, and a universal one beside them. The Go core
    // is a native library per architecture - 20.9 MB on arm64 once the
    // Configs section's sing-box is in it (measured 2026-11-14) - and the
    // universal APK carries all four: over 100 MB to download over a
    // filtered line, where most phones need only the arm64-v8a one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // Compressed inside the APK and unpacked when it installs: the core
    // compresses to about 38 % of its size, and the download is what the
    // line pays for. Stored uncompressed (the default) the APK is bigger by
    // the difference on every download.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].assets.directories.add(generatedAssets.get().asFile.path)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.named("preBuild") {
    dependsOn(copyInstaller)
    doFirst {
        if (!mirageAar.exists()) {
            throw GradleException(
                "android/app/libs/mirage.aar is missing. Build the Go core first:\n" +
                    "    mobile/build-android.sh\n" +
                    "It writes the .aar there as well as into mobile/.")
        }
    }
}

dependencies {
    implementation(files(mirageAar))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)

    // Where account passwords live: a keystore-held key, so run-as on this
    // debuggable build cannot read them out of shared_prefs.
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; the tests need the real one.
    testImplementation(libs.json)
}
