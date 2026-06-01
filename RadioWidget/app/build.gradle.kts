plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "de.radiowidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.radiowidget"
        minSdk        = 31
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines)
}
