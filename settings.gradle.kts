pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API（非官方镜像，避免 jcenter）
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "OpenWorldBox"
include(":app")
