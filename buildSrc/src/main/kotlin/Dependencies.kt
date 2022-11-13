object AndroidVersions {
    const val compileSdk = 33
    const val minSdk = 23
    const val targetSdk = 30
    const val versionCode = 97
    const val versionName = "1.5.9"
    const val ndk = "23.1.7779620"
    const val kotlin = "1.7.20"
}

object Plugins {
    const val androidApplication = "com.android.application"
    const val googleServices = "com.google.gms.google-services"
    const val kapt = "kapt"
    const val kotlinParcelize = "kotlin-parcelize"
    const val kotlinAndroid = "android"
    const val jetbrainsKotlin = "org.jetbrains.kotlin.android"
    const val kotlinSerialization = "org.jetbrains.kotlin.plugin.serialization"
    val gradleVersions = PluginClass("com.github.ben-manes.versions", "0.42.0")
    val kotlinter = PluginClass("org.jmailen.kotlinter", "3.12.0")
}

data class PluginClass(val name: String, val version: String) {
    override fun toString() = "$name:$version"
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
