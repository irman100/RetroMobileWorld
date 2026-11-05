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
        versionCode = 3
        versionName = "0.3.0-rm217-firmware"
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
}
