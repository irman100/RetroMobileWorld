import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

android {
    namespace = "org.retromobile.world"
    compileSdk = 36

    sourceSets["main"].assets.srcDir("$rootDir/profiles")
    sourceSets["main"].assets.srcDir("$buildDir/generated/c3emuAssets")

    defaultConfig {
        applicationId = "org.retromobile.world"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1-c3emu-rm614"
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
}

val c3ProfilePhotoFile = layout.buildDirectory.file("generated/c3emuAssets/c3emu/75-205-263-02.png")
val c3ProfilePhotoAttributionFile = layout.buildDirectory.file("generated/c3emuAssets/c3emu/PROFILE_IMAGE_SOURCE.txt")

// The user-selected front-facing C3-00 reference image lives in the repository's
// main branch. Fetch the exact reviewed Git blob and verify its Git object hash
// before putting it in the APK. No runtime network access is used.
val fetchC3ProfilePhoto = tasks.register("fetchC3ProfilePhoto") {
    outputs.files(c3ProfilePhotoFile, c3ProfilePhotoAttributionFile)
    doLast {
        val photo = c3ProfilePhotoFile.get().asFile
        val attribution = c3ProfilePhotoAttributionFile.get().asFile
        val expectedGitBlobSha1 = "98edc0abadb2e37afa4e0d3168fb8b186aea64b1"
        val sourceCommit = "bd44163d8c059cb9840eb1ab9c28da7228e44109"
        val sourceUrl =
            "https://raw.githubusercontent.com/irman100/RetroMobileWorld/$sourceCommit/docs/75-205-263-02.png"

        val connection = URL(sourceUrl).openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "RetroMobileWorld/0.3 (profile asset retrieval)")
        }
        val bytes = connection.getInputStream().use { it.readBytes() }
        val gitHeader = ("blob ${bytes.size}" + 0.toChar()).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(gitHeader)
        val actualGitBlobSha1 = digest.digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

        check(bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) { "The C3-00 profile image is not a valid PNG." }
        check(actualGitBlobSha1 == expectedGitBlobSha1) {
            "C3-00 profile image integrity check failed: expected $expectedGitBlobSha1, got $actualGitBlobSha1."
        }

        photo.parentFile.mkdirs()
        photo.writeBytes(bytes)
        attribution.parentFile.mkdirs()
        attribution.writeText(
            """User-selected Nokia C3-00 profile image
Source: docs/75-205-263-02.png
Source commit: $sourceCommit
Git blob SHA-1: $expectedGitBlobSha1
Packaged unchanged as an Android asset; the guest LCD is drawn separately over its screen opening.
""".trimIndent(),
            Charsets.UTF_8
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(fetchC3ProfilePhoto)
}
