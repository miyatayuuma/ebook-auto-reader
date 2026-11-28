plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.github.ebookautoreader.talkbackprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ebookautoreader.talkbackprobe"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "p2.7a0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
