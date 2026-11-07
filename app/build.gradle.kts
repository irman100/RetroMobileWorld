plugins {
    id("com.android.application")
}

android {
    namespace = "org.retromobile.world"
    compileSdk = 36

    sourceSets["main"].assets.srcDir("$rootDir/profiles")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/c3emuAssets"))

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

val c3PhotoFile = layout.buildDirectory.file("generated/c3emuAssets/c3emu/dienthoai.jpg")
val c3PhotoAttributionFile = layout.buildDirectory.file("generated/c3emuAssets/c3emu/ATTRIBUTION.txt")

// The original Commons photograph is fetched at build time, verified by SHA-1,
// then packaged as an ordinary app asset (no network access at runtime).
val fetchC3ShellPhoto = tasks.register("fetchC3ShellPhoto") {
    outputs.files(c3PhotoFile, c3PhotoAttributionFile)
    doLast {
        val photo = c3PhotoFile.get().asFile
        val attribution = c3PhotoAttributionFile.get().asFile
        val expectedSha1 = "2dbd3412ea6cdc2477281b9cc210294f2d433a88"

        fun sha1(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }

        val existing = if (photo.isFile) photo.readBytes() else null
        val verifiedExisting = existing != null && sha1(existing) == expectedSha1
        if (!verifiedExisting) {
            val connection = java.net.URL(
                "https://upload.wikimedia.org/wikipedia/commons/4/4c/Dienthoai.JPG"
            ).openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "RetroMobileWorld/0.3 (build asset retrieval)")
            }
            val bytes = connection.getInputStream().use { it.readBytes() }
            check(bytes.size == 1_339_157 && sha1(bytes) == expectedSha1) {
                "Nokia C3-00 Commons photo failed its size/SHA-1 verification."
            }
            photo.parentFile.mkdirs()
            photo.writeBytes(bytes)
        }

        if (!attribution.isFile) {
            attribution.parentFile.mkdirs()
            attribution.writeText(
                """Photograph: Dienthoai.JPG (23 December 2011)
Author: Trongphu
Source: https://commons.wikimedia.org/wiki/File:Dienthoai.JPG
License: Creative Commons Attribution-ShareAlike 3.0 Unported (CC BY-SA 3.0)
License text: https://creativecommons.org/licenses/by-sa/3.0/

The original JPEG is included unchanged. The emulator LCD is rendered separately
over the photographed display at runtime; invisible touch regions are aligned
with the photographed physical keys.
""".trimIndent(),
                Charsets.UTF_8
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(fetchC3ShellPhoto)
}
