plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ebookautoreader.lab"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ebookautoreader.lab"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "p1"
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("fixture-assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val stageFixtures by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../p1/out/fixtures")) {
        include("pages/*.png", "speech.json")
    }
    into(layout.buildDirectory.dir("fixture-assets"))
    doFirst {
        val fixtures = rootProject.file("../p1/out/fixtures")
        check(fixtures.resolve("speech.json").isFile && fixtures.resolve("pages").list()?.isNotEmpty() == true) {
            "Run python p1/fixtures.py and python p1/reading.py prepare before building"
        }
    }
}

tasks.named("preBuild") { dependsOn(stageFixtures) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation(project(":ndl"))
}
