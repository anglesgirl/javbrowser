plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.example.javbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.javbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 123
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Upstream 1.2.1 is author-release-signed; the same package
            // name would not install over it. The suffix lets this
            // build install side-by-side with upstream.
            applicationIdSuffix = ".ech"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Keep the Firebase SDKs compatible with this project's Kotlin 1.9 toolchain.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // PhotoView for pinch-to-zoom image display
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    
    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Local HTTP proxy for CDN-protected video streaming
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // HTML parsing for JavDB scraper
    implementation("org.jsoup:jsoup:1.17.2")

    // LocalBroadcastManager for in-app event notification
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // WebKit extras: addDocumentStartJavaScript (inject before page JS)
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // App-layer ECH (Conscrypt) + HTTP/3 (Cronet) for search/page fetch.
    // OkHttp, AGP, Kotlin and the SDK levels are deliberately NOT upgraded.
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    implementation("org.chromium.net:cronet-embedded:143.7445.0")
    implementation("org.mp4parser:muxer:1.9.56")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
