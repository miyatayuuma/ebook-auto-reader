plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ebookautoreader.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ebookautoreader"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "p2.3"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("fixture-assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val stageFixtures by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../p1/out/fixtures")) {
        include("pages/join-*.png", "pages/short-*.png", "pages/body-medium.png", "pages/body-dense.png",
            "pages/body-bottom.png", "pages/body-narrow.png", "pages/chapter.png")
    }
    into(layout.buildDirectory.dir("fixture-assets"))
}

tasks.named("preBuild") { dependsOn(stageFixtures) }

dependencies {
    implementation(project(":ndl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
