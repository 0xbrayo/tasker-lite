plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release versioning: CI exports RELEASE_VERSION_NAME from the git tag (e.g. "v1.2.3")
// so a published APK reports the version it was tagged with. Local builds keep 1.0.0.
val taggedVersionName: String? = System.getenv("RELEASE_VERSION_NAME")
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }

/** "1.2.3" → 10203, monotonic for major/minor/patch each below 100. */
fun versionCodeFor(name: String?): Int {
    val parts = name?.substringBefore('-')?.split('.') ?: return 1
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return 1
    val minor = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

android {
    namespace = "com.taskerlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taskerlite"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeFor(taggedVersionName)
        versionName = taggedVersionName ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI/release: optional keystore via env (see .github/workflows/release.yml).
    // Without RELEASE_STORE_FILE, release is signed with the debug keystore so
    // :app:assembleRelease still produces an installable APK for personal tags.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("RELEASE_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasReleaseKeystore = !System.getenv("RELEASE_STORE_FILE").isNullOrBlank()
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
