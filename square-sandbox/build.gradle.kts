plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "org.portlandkollel.kiosk.square"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation("com.squareup.sdk:mobile-payments-sdk:2.6.1")
    implementation("com.squareup.sdk:mockreader-ui:2.6.1")
}
