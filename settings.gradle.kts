pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories {
    google(); mavenCentral()
    exclusiveContent {
        forRepository { maven("https://sdk.squareup.com/public/android/") }
        filter { includeGroup("com.squareup.sdk") }
    }
} }
rootProject.name = "KollelGivingKiosk"
include(":app", ":core", ":square-sandbox", ":device-security")
