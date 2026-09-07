import java.util.Properties

// The version a release build carries comes from CI; a local build keeps the
// checked-in default so `assembleDebug` needs no environment at all.
val releaseVersionName: String = System.getenv("SWITCHBOARD_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "0.1.0"

// Derived from the name rather than a run number: a rebuild of a version must
// produce the same code, and every later version a higher one. A run number
// counts builds, not versions, and gets both wrong.
//
// The pre-release counter has to be in here. Play refuses two uploads sharing a
// version code, so 0.1.1-alpha.1 and 0.1.1-alpha.2 collapsing to one code would
// make the second un-uploadable. The offset orders a patch's own lifecycle:
// alpha.N < beta.N < the stable release of that same patch, and every one of
// them below the next patch's first alpha.
val releaseVersionCode: Int = run {
    val core = releaseVersionName.substringBefore('-').split('.')
    val major = core.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = core.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = core.getOrNull(2)?.toIntOrNull() ?: 0

    val suffix = releaseVersionName.substringAfter('-', "")
    val counter = suffix.substringAfter('.', "").toIntOrNull() ?: 0
    val offset = when {
        suffix.startsWith("alpha") -> counter.coerceIn(1, 499)
        suffix.startsWith("beta") -> 500 + counter.coerceIn(1, 498)
        else -> 999 // a stable release outranks every pre-release of its patch
    }

    major * 10_000_000 + minor * 100_000 + patch * 1_000 + offset
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.switchboard.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.switchboard.app"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The app ships no translations, so every other locale in the AndroidX
        // and Compose artifacts is dead weight in the APK.
        resourceConfigurations += listOf("en")
    }

    // The signature and build metadata of every jar on the classpath, none of
    // which is read at runtime. BouncyCastle alone accounts for most of it.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }

    // Signing is configured only when CI (or a local developer) supplies a
    // keystore. Absent one the release build is still produced, unsigned, so a
    // fork can run the release workflow without being handed a signing key.
    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
        val keystoreFile = keystorePath?.let(::File)?.takeIf { it.isFile }
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                // v1 too: minSdk is 26, and APK Signature Scheme v2 alone is
                // refused by some sideload paths that still read the JAR
                // signature.
                enableV1Signing = true
                enableV2Signing = true
            }
        } else if (System.getenv("CI") != null) {
            logger.warn("ANDROID_KEYSTORE_FILE is unset or missing; the release build will be UNSIGNED.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    // ponytail: deprecated; kept only so HostStore can read the pre-Keystore
    // prefs file during migration. Drop one release after that ships.
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.embedded)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)

    testImplementation(libs.junit)
}
