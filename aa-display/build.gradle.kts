import java.util.zip.ZipFile

// Release CI supplies calendar versions; local builds keep development defaults.
val confVersionName = providers.environmentVariable("AADISPLAY_VERSION_NAME").orElse("1.0.0-dev").get()
val confVersionCode = providers.environmentVariable("AADISPLAY_VERSION_CODE").orElse("1").get().toInt()
require(confVersionName.isNotBlank()) { "AADISPLAY_VERSION_NAME must not be blank" }
require(confVersionCode in 1..2_100_000_000) { "AADISPLAY_VERSION_CODE is outside the supported Android range" }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    //kotlin("android")
    id("kotlin-android")
    id("dev.rikka.tools.refine") version "4.4.0"
}

android {
    val buildTime = System.currentTimeMillis()
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aadisplay101.app"
        minSdk = 31
        targetSdk = 37
        versionCode = confVersionCode
        versionName = confVersionName
        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }

    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "kotlin/**"
            )
        )
    }
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            sourceSets.getByName("main").java.srcDir(File("build/generated/ksp/release/kotlin"))
        }
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

    }
    
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = variant.versionName.replace("#", "-")
            output.outputFileName = "aa-display-${versionName}.apk"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        viewBinding = true
        aidl = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources.additionalParameters += mutableListOf("--allow-reserved-package-id", "--package-id", "0x64")

    namespace = "io.github.nitsuya.aa.display"
    buildToolsVersion = "35.0.0"
}

fun Task.verifyXposedMetadata(apkDirName: String) {
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/$apkDirName").get().asFile
        val apk = apkDir.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
            ?: error("No $apkDirName APK found in ${apkDir.absolutePath}")

        val requiredEntries = listOf(
            "META-INF/xposed/java_init.list",
            "META-INF/xposed/module.prop",
            "META-INF/xposed/scope.list",
        )
        val entries = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            val zipEntries = zip.entries()
            while (zipEntries.hasMoreElements()) {
                entries += zipEntries.nextElement().name
            }
        }
        val missing = requiredEntries.filterNot(entries::contains)
        check(missing.isEmpty()) {
            "Missing libxposed metadata in ${apk.name}: ${missing.joinToString()}"
        }
        println("Verified libxposed metadata in ${apk.name}: ${requiredEntries.joinToString()}")
    }
}

tasks.register("verifyDebugXposedMetadata") {
    dependsOn("assembleDebug")
    verifyXposedMetadata("debug")
}

tasks.register("verifyReleaseXposedMetadata") {
    dependsOn("assembleRelease")
    verifyXposedMetadata("release")
}

afterEvaluate {
    tasks.named("assembleDebug").configure {
        finalizedBy("verifyDebugXposedMetadata")
    }
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
//    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("dev.rikka.rikkax.appcompat:appcompat:1.6.1")
    implementation("dev.rikka.rikkax.material:material-preference:2.0.0")

    //kotlinx-coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    //ViewBindingUtil
    implementation("com.github.matsudamper:ViewBindingUtil:0.1")

    compileOnly(project(":lib-stub"))
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("com.github.topjohnwu.libsu:core:5.2.0")
    implementation("org.luckypray:dexkit:2.2.0")
//    implementation("com.github.martoreto:aauto-sdk:v4.7")
    implementation(files("./libs/aauto.aar"))

    //lifecycle
    val lifecycleVersion = "2.9.3"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")
}
