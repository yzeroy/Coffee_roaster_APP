plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Verze aplikace. Číslo sestavení (versionCode) dodává GitHub Actions, lokálně 1.
val appVersionName = "0.1.0"
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// Podpisový klíč se nikdy neukládá do repozitáře. Když jsou proměnné prostředí
// nastavené (GitHub Secrets), APK se podepíše jím, jinak ladicím klíčem.
val signingKeystore = System.getenv("SIGNING_KEYSTORE")
val hasReleaseSigning = !signingKeystore.isNullOrBlank() && file(signingKeystore).exists()

android {
    namespace = "cz.prazicka"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.prazicka"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_REPO", "\"yzeroy/Coffee_roaster_APP\"")
    }

    flavorDimensions += "varianta"
    productFlavors {
        create("provozni") {
            dimension = "varianta"
            buildConfigField("String", "VARIANT", "\"release\"")
            buildConfigField("boolean", "IS_TEST", "false")
            resValue("string", "app_name", "Pražička")
        }
        create("testovaci") {
            dimension = "varianta"
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test.$ciVersionCode"
            buildConfigField("String", "VARIANT", "\"test\"")
            buildConfigField("boolean", "IS_TEST", "true")
            resValue("string", "app_name", "Pražička TEST")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystore!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
