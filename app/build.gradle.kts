plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

providers.gradleProperty("codexBuildDir").orNull?.let { customBuildDir ->
    layout.buildDirectory.set(file(customBuildDir))
}

val appVersionCode = 104
val appVersionName = "1.04"
val appDisplayVersion = "v$appVersionName ($appVersionCode)"
val appDebugDisplayVersion = "v$appVersionName-dev ($appVersionCode)"
val isProBuild = providers.gradleProperty("batteryCurrentPro")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "com.analogwings.batterycurrent"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.analogwings.batterycurrent"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("boolean", "IS_PRO_VERSION", isProBuild.toString())
        buildConfigField("String", "APP_DISPLAY_VERSION", "\"$appDisplayVersion\"")
        buildConfigField("String", "APP_BUILD_TRACK", "\"release\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            buildConfigField("String", "APP_DISPLAY_VERSION", "\"$appDebugDisplayVersion\"")
            buildConfigField("String", "APP_BUILD_TRACK", "\"dev\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "APP_DISPLAY_VERSION", "\"$appDisplayVersion\"")
            buildConfigField("String", "APP_BUILD_TRACK", "\"release\"")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
