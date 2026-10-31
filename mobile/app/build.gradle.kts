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
    alias(libs.plugins.kotlin.serialization)
}

// The shared Rust core (../../core) is built for each ABI by cargo-ndk into
// src/main/jniLibs before Gradle packages it. `-Pabis=x86_64` narrows it for a
// quick emulator build.
val rustAbis: List<String> = (findProperty("abis") as String?)?.split(',')?.map { it.trim() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val buildRustCore by tasks.registering(Exec::class) {
    val repo = rootProject.projectDir.parentFile
    workingDir = repo
    val release = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    commandLine(
        buildList {
            add("cargo"); add("ndk")
            rustAbis.forEach { add("-t"); add(it) }
            add("-o"); add(project.file("src/main/jniLibs").absolutePath)
            add("build"); add("-p"); add("switchboard-core")
            if (release) add("--release")
        },
    )
    inputs.dir(File(repo, "core/src"))
    inputs.file(File(repo, "core/Cargo.toml"))
    outputs.dir(project.file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildRustCore) }

android {
    namespace = "com.aiyu.switchboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aiyu.switchboard"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("int", "DEFAULT_PORT", "9427")
        ndk { abiFilters += rustAbis }
    }

    signingConfigs {
        create("release") {
            // Only from the environment (CI secrets); a local release build is unsigned.
            val path = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!path.isNullOrBlank() && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release app and talks to the dev daemon (port 9428).
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("int", "DEFAULT_PORT", "9428")
            resValue("string", "app_name", "Switchboard Dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            resValue("string", "app_name", "Switchboard")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        // M3 Expressive is still experimental in material3 1.5 alphas.
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
