plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin compiler version.
    // litertlm-android 0.17.1 is built with Kotlin 2.4, so the compiler must be >= 2.3.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
