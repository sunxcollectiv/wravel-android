import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Push needs a Firebase project. Drop its google-services.json into app/ to turn it
// on; without one the app builds and runs with push registration skipped.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

/// Local secrets (API keys), never committed: copy Secrets.example.json to
/// app/src/main/assets/Secrets.json and fill in real values. Read at runtime by
/// `Secrets`, and here for the keys the manifest needs.
val secrets: Map<*, *> = file("src/main/assets/Secrets.json")
    .takeIf { it.exists() }
    ?.let { JsonSlurper().parse(it) as? Map<*, *> }
    ?: emptyMap<String, String>()

android {
    namespace = "earth.outbound"
    compileSdk = 35

    defaultConfig {
        applicationId = "earth.outbound"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["googleMapsApiKey"] = secrets["GoogleMapsAPIKey"] ?: secrets["GooglePlacesAPIKey"] ?: ""
    }

    buildTypes {
        // Debug builds talk to the local dev server when running on the emulator (which
        // reaches this machine at 10.0.2.2) and to production on a physical device;
        // release builds always use production. To point a debug build somewhere else on
        // both (another port, or this machine's LAN address for a phone), pass
        // -Poutbound.apiBaseUrl=http://… or set it in ~/.gradle/gradle.properties.
        debug {
            val override = providers.gradleProperty("outbound.apiBaseUrl").orNull
            buildConfigField("String", "API_BASE_URL", "\"${override ?: "https://api.wravel.app"}\"")
            buildConfigField("String", "EMULATOR_API_BASE_URL", "\"${override ?: "http://10.0.2.2:4830"}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.wravel.app\"")
            buildConfigField("String", "EMULATOR_API_BASE_URL", "\"https://api.wravel.app\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.billing.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
