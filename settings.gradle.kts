pluginManagement {
    repositories {
        google()
        // The Kotlin 2.2.10 compiler artifact currently redirects from Maven
        // Central to GitHub Releases, which intermittently returns HTTP 504.
        // Restrict this mirror to that exact module; all other plugin
        // dependencies continue to use the normal repositories below.
        maven {
            name = "kotlinCompilerEmbeddableMirror"
            url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
            content {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Same narrowly-scoped fallback for project/build-tool dependencies.
        maven {
            name = "kotlinCompilerEmbeddableMirror"
            url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
            content {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RetroMobileWorld"
include(":app")
