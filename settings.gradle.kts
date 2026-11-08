pluginManagement {
    repositories {
        google()
        // Kotlin 2.2.10 compiler-embeddable redirects from Maven Central to
        // GitHub Releases, which intermittently returns HTTP 504 in CI.
        // WSO2 hosts the exact artifact; force this one module to resolve
        // from that Maven repository, without Gradle-module metadata redirects.
        exclusiveContent {
            forRepository {
                maven {
                    name = "kotlinCompilerEmbeddableMirror"
                    url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
                    metadataSources {
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
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
        // Apply the same exclusive source to build-tool dependencies.
        exclusiveContent {
            forRepository {
                maven {
                    name = "kotlinCompilerEmbeddableMirror"
                    url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
                    metadataSources {
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RetroMobileWorld"
include(":app")
