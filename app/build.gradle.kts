plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val gitSha = (System.getenv("GITHUB_SHA") ?: "local").take(7)

// Libraries the NPU path needs at runtime. CI fetches/builds them into jniLibs (never committed).
val requiredNativeLibs = listOf(
    "libQnnHtp.so",
    "libQnnSystem.so",
    "libQnnHtpV69Stub.so",
    "libQnnHtpV69Skel.so",
    "libLiteRtDispatch_Qualcomm.so",
    "libllama_server.so", // llama.cpp server executable for CPU models (exec'd, not dlopen'd)
)

android {
    namespace = "com.npuchat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.npuchat"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Fixed debug key, committed on purpose: every CI build must be signed with the same key
        // so a new APK installs as an update. Uninstalling would delete the 2 GB model.
        getByName("debug") {
            storeFile = file("debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: the dispatch lookup readdirs that directory
            // and the DSP loads the skel from it via ADSP_LIBRARY_PATH.
            useLegacyPackaging = true
            // Ship the Qualcomm/dispatch libs byte-for-byte. The skel is a Hexagon binary that the
            // arm64 strip step must not touch.
            keepDebugSymbols += listOf("**/libQnn*.so", "**/libLiteRtDispatch_Qualcomm.so", "**/libllama_server.so")
        }
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
}

val checkNativeLibs = tasks.register("checkNativeLibs") {
    val dir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val enforce = providers.gradleProperty("requireNativeLibs").map { it.toBoolean() }.orElse(false)
    doLast {
        val missing = requiredNativeLibs.filterNot { dir.file(it).asFile.exists() }
        if (missing.isNotEmpty()) {
            val msg = "Missing NPU native libs in ${dir.asFile}: $missing"
            if (enforce.get()) throw GradleException(msg) else logger.warn("WARNING: $msg (APK will not run on the NPU)")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNativeLibs) }
