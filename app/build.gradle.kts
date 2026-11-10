plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "org.portlandkollel.kiosk"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "org.portlandkollel.kiosk"
        minSdk = 28
        targetSdk = 36
        versionCode = 18
        versionName = "0.5.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "REAL_CHARGES_ENABLED", "false")
        // Legacy Sandbox flavor only; the complete app receives public config over trusted USB.
        val sandboxApp = providers.environmentVariable("SQUARE_SANDBOX_APPLICATION_ID").orNull.orEmpty()
        val sandboxLocation = providers.environmentVariable("SQUARE_SANDBOX_LOCATION_ID").orNull.orEmpty()
        require(sandboxApp.isEmpty() || Regex("sandbox-sq0idb-[A-Za-z0-9_-]{1,128}").matches(sandboxApp))
        require(sandboxLocation.isEmpty() || Regex("[A-Za-z0-9_-]{1,192}").matches(sandboxLocation))
        buildConfigField("String", "LEGACY_SANDBOX_APPLICATION_ID", "\"$sandboxApp\"")
        buildConfigField("String", "LEGACY_SANDBOX_LOCATION_ID", "\"$sandboxLocation\"")
    }
    if (providers.gradleProperty("includeSquareSandbox").orNull == "true") {
        flavorDimensions += "payments"
        productFlavors {
            create("offline") { dimension = "payments" }
            create("squareSandbox") {
                dimension = "payments"
                applicationIdSuffix = ".sandbox"
                versionNameSuffix = "-sandbox"
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Real charges only in the organization-signed live release (see .github/workflows/live-release.yml).
            val live = providers.gradleProperty("kollelLiveRelease").orNull == "true"
            buildConfigField("boolean", "REAL_CHARGES_ENABLED", live.toString())
        }
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(project(":square-sandbox"))
    implementation(project(":device-security"))
    implementation("androidx.activity:activity-ktx:1.12.2")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
