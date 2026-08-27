plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bitperfect.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.bitperfect.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        // Debug is what is distributed for on-device testing, and it must fit
        // under platform and hosting size limits. Code shrinking (R8) removes
        // the large amount of unused library code — chiefly the thousands of
        // vector assets in material-icons-extended — that otherwise pushed the
        // APK past 100 MB. Obfuscation is disabled in proguard-rules.pro, so
        // shrinking is the only transform applied and the JNI boundary is safe.
        debug {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

ksp {
    // Export the Room schema so migrations can be reviewed in version control.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Material 3
    implementation("com.google.android.material:material:1.11.0")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Room (KSP-processed; see the `ksp` block above for the schema location)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Coil - album artwork loading for Compose
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette - extract a vibrant accent colour from album art
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media3
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")

    // AndroidX Media (MediaSessionCompat, MediaBrowserServiceCompat)
    implementation("androidx.media:media:1.7.0")

    // Guava (ListenableFuture, Futures)
    implementation("com.google.guava:guava:32.1.3-android")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")

    // Compose Activity
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Compose Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Testing
    // The unit tests under src/test are written against JUnit 5 (Jupiter).
    // Only JUnit 4 used to be declared here, so they never compiled or ran.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}


// The JVM unit tests use JUnit 5, which needs the JUnit Platform runner.
// Instrumented tests keep the default JUnit 4 runner.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
