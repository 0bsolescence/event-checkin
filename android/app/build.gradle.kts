plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.eventcheckin"
    compileSdk = 35

    defaultConfig {
        // Deliberate placeholder. Google Play REJECTS com.example.* — choosing
        // the permanent applicationId is a naming decision that must happen
        // before any store submission, because it can never change afterward.
        applicationId = "com.example.eventcheckin"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    // JVM-only: the tested logic (CSV parsing, header detection, color rules)
    // is deliberately free of Android types, so no Robolectric is needed.
    testImplementation(libs.junit)
}
