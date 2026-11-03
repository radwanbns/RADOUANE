plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xraytunnel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xraytunnel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // استبدله بمفتاح توقيع خاص بك عند النشر
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // ics-openvpn مبنيّة بـ Kotlin أحدث من مترجم المشروع؛ نتجاوز فحص إصدار الـ metadata
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // نواة Xray (libv2ray.aar) تُوضع في app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Google AdMob SDK: App Open, Interstitial, Rewarded and Banner ads.
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    // SSH client (fork مُحدَّث من JSch) لنفق SSH / SSH-WebSocket.
    implementation("com.github.mwiede:jsch:0.2.20")
    // ics-openvpn (app/libs/ics-openvpn.aar) يعتمد على هذه المكتبات؛ الـ AAR لا يجلبها تلقائياً.
    // إن اشتكى البناء من كلاس ناقص من androidx/kotlin أضف المكتبة المقابلة هنا.
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
