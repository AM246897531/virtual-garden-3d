plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.mishnehtorah"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.mishnehtorah"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    // JVM Robolectric tests read the ORIGINAL corpus and exercise the REAL
    // Android SQLite + scoring engine without an emulator or packaging an APK.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
                it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
                it.jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Robolectric 4.14 matches Gradle 8.10 / AGP 8.7 and Android API 35.
    testImplementation("org.robolectric:robolectric:4.14")
}
