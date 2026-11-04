plugins {
    id("com.android.application")
}

android {
    namespace = "org.retromobile.world"
    compileSdk = 36

    sourceSets["main"].assets.srcDir("$rootDir/profiles")

    defaultConfig {
        applicationId = "org.retromobile.world"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-bootstrap"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
