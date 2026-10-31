// The version a release build carries comes from CI; a local build keeps the
// checked-in default so `assembleDebug` needs no environment at all.
val releaseVersionName: String = System.getenv("SWITCHBOARD_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "0.0.0"

// Derived from the name so a rebuild of a version yields the same code and every
// later version a higher one. The pre-release counter is part of it: Play refuses
// two uploads sharing a version code.
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
}

android {
    namespace = "com.switchboard.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.switchboard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
