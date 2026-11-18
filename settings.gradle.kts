pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "OpenZiloConnectReleases"
                    url = uri("https://github.com/ziloai/OpenZilo-Connect/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.github.ziloai", "connect-api") }
        }
    }
}
rootProject.name = "OpenZiloInput"
include(":app")
