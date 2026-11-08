import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.seniorenlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seniorenlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.0.0"
    }

    // Telegram API credentials: read from telegram.properties (git-ignored). See TELEGRAM.md.
    val telegramProps = Properties().apply {
        val f = rootProject.file("telegram.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    defaultConfig {
        buildConfigField("int", "TELEGRAM_API_ID", (telegramProps.getProperty("apiId") ?: "0").trim().ifEmpty { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"" + (telegramProps.getProperty("apiHash") ?: "").trim() + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets.getByName("main").jniLibs.srcDir(File(rootProject.projectDir, "app/build/tdlib/jniLibs"))

    // Release signing: reads keystore.properties (git-ignored) from the project root.
    // See SIGNING.md. Without that file the release build is left unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasSigning = keystoreProps.getProperty("storePassword").orEmpty().isNotEmpty()

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword").orEmpty().ifEmpty { keystoreProps.getProperty("storePassword") }
            }
        }
    }

    buildTypes {
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compress libtdjson.so inside the APK (much smaller download; extracted at install)
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Downloads the prebuilt TDLib (libtdjson.so) at a pinned version and verifies its SHA-256.
val tdlibVersion = "v1.8.65"
val tdlibSha256 = "eb777d3e7baedeb02871c691b2090daa1bc51baf9215a81bc55bf54edb76df2b"
val fetchTdlib by tasks.registering {
    val archive = layout.buildDirectory.file("tdlib/jniLibs.tar.gz")
    val outDir = layout.buildDirectory.dir("tdlib/jniLibs")
    outputs.dir(outDir)
    doLast {
        val f = archive.get().asFile
        f.parentFile.mkdirs()
        fun sha(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (!f.exists() || sha(f) != tdlibSha256) {
            URI("https://github.com/up9cloud/android-libtdjson/releases/download/$tdlibVersion/jniLibs.tar.gz")
                .toURL().openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        check(sha(f) == tdlibSha256) { "TDLib archive SHA-256 mismatch" }
        copy {
            from(tarTree(resources.gzip(f))) { include("jniLibs/arm64-v8a/**", "jniLibs/armeabi-v7a/**"); eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }; includeEmptyDirs = false }
            into(outDir)
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchTdlib) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.material)
    implementation(libs.coil.compose)

    // QR code for Telegram sign-in with a helper
    implementation("com.google.zxing:core:3.5.3")

    // Retrofit for Updates
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // CameraX for Magnifier
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Media3 for Radio Playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
