plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mulin.tvdy"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "mulin.tvdy"
        minSdk = 21
        targetSdk = 35
        versionCode = 65
        versionName = "2.1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
dependencies {
    // 1.14.0 is the last release with minSdk 21; 1.15.0+ require 23/24.
    implementation("androidx.webkit:webkit:1.14.0")
    // 1.8.x is the last media3 line with minSdk 21; 1.9.0+ requires 23.
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    // CacheDataSource / SimpleCache for the media byte cache (see
    // MediaCacheStore); exoplayer pulls this in transitively, but the cache
    // API is used directly so it is declared explicitly.
    implementation("androidx.media3:media3-datasource:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    // Pure-Java QR encoder for the cookie-handoff URL (see QrCodeGenerator);
    // 3.4.1 predates zxing's newer releases' Android API 24+ requirement,
    // so it stays compatible with this project's minSdk 21.
    implementation("com.google.zxing:core:3.4.1")
    // Renders the startup spinner (see PlayerActivity's splash overlay);
    // 6.7.1 still supports minSdk 21.
    implementation("com.airbnb.android:lottie:6.7.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar stubs throw at runtime).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
