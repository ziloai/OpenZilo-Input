pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenZiloInput"
include(":app", ":connect-api", ":ring-core")
val connect = file(providers.gradleProperty("connectRepoDir").orElse("vendor/connect").get())
project(":connect-api").projectDir = connect.resolve("connect-api")
project(":ring-core").projectDir = connect.resolve("ring-core")
