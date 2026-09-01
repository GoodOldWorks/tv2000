plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.legacy.kapt)
    alias(libs.plugins.compose.compiler)
}

val releaseKeystorePath = providers.environmentVariable("TV2000_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("TV2000_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("TV2000_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("TV2000_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "com.tv2000.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tv2000.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseKeystorePath?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storePassword = requireNotNull(releaseStorePassword) {
                    "TV2000_RELEASE_STORE_PASSWORD is required for a signed release"
                }
                keyAlias = requireNotNull(releaseKeyAlias) {
                    "TV2000_RELEASE_KEY_ALIAS is required for a signed release"
                }
                keyPassword = requireNotNull(releaseKeyPassword) {
                    "TV2000_RELEASE_KEY_PASSWORD is required for a signed release"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.smbj)

    kapt(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
