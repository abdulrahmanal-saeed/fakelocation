plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val wayshiftPlay = providers.gradleProperty("wayshiftPlay").map(String::toBoolean).orElse(false).get()
fun setting(name: String): String = providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orElse("").get()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.abdulrahman.fakelocation"
    compileSdk = 36

    defaultConfig {
        applicationId = if (wayshiftPlay) "com.abdulrahman.fakelocation.play" else "com.abdulrahman.fakelocation"
        buildConfigField("boolean", "PLAY_BUILD", wayshiftPlay.toString())
        for (name in listOf("PHOTON_URL", "WAYSHIFT_API_URL", "FIREBASE_APP_ID", "FIREBASE_PROJECT_ID", "FIREBASE_API_KEY", "FIREBASE_WEB_CLIENT_ID", "ADMOB_BANNER_ID", "ADMOB_REWARDED_ID")) {
            buildConfigField("String", name, quoted(setting(name)))
        }
        manifestPlaceholders["admobAppId"] = setting("ADMOB_APP_ID").ifBlank { "ca-app-pub-3940256099942544~3347511713" }
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "2.11.0"
        // The vector map (MapLibre) brings native code: only the phone's kind (64-bit ARM) keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (wayshiftPlay && setting("PLAY_UPLOAD_STORE").isNotBlank()) {
            create("playUpload") {
                storeFile = file(setting("PLAY_UPLOAD_STORE"))
                storePassword = setting("PLAY_UPLOAD_PASSWORD")
                keyAlias = setting("PLAY_UPLOAD_ALIAS")
                keyPassword = setting("PLAY_UPLOAD_KEY_PASSWORD")
            }
        }
        // Fixed key committed to the repo so every CI build can be installed
        // over the previous one without uninstalling. Personal use only.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        // Smaller, faster APK (R8). Same key as the debug build, so either installs over the other.
        getByName("release") {
            signingConfig = if (wayshiftPlay) signingConfigs.findByName("playUpload") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // The vector map's native library is large: keep it compressed in the APK (unpacked when installed).
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // The OpenGL build: the default one draws with Vulkan, which stayed blank on the phone.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")

    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.android.billingclient:billing:8.3.0")
    implementation("com.google.android.gms:play-services-ads:25.0.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
