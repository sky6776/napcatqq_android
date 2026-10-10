plugins {
    id("com.android.application")
}

android {
    namespace = "dev.napcathost"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.napcathost"
        minSdk = 26
        // Android 10 allows app-private executables for apps targeting API 28.
        // This project is intentionally a private, sideloaded host for one device.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("../runtime-packages")
        }
    }

    androidResources {
        noCompress += "zst"
        // runtime-packages/ is mounted as an asset root only to pick up the
        // .zst runtime archive; keep its JSON bookkeeping out of the APK.
        // Prefix is aapt's default ignore pattern plus the two JSON files.
        ignoreAssetsPattern =
            "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*.alb:manifest.json:build-info.json"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    lint {
        // This is a device-specific private sideload build, not a Play/ChromeOS artifact.
        disable += setOf(
            "ExpiredTargetSdkVersion",
            "ChromeOsAbiSupport",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable"
        )
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")

    testImplementation("junit:junit:4.13.2")
}
