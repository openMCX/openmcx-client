plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.openmcx.client.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.openmcx.client"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // The screens with sample data and no engine, installed beside the client to try the look (see src/uiPreview).
        create("uiPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uipreview"
            versionNameSuffix = "-uipreview"
            matchingFallbacks += "debug"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android runtime (~200 MB) is kept beside the Gradle cache, so it is fetched only once.
        unitTests.all { test ->
            val dir = gradle.gradleUserHomeDir.resolve("robolectric-home")
            test.systemProperty("user.home", dir.path)
            test.doFirst { dir.mkdirs() }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.telecom)
    implementation(libs.browser)
    implementation(libs.zxing.embedded)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
