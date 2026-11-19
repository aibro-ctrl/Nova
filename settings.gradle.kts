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
    }
}

rootProject.name = "nova"

// :core — платформенно-независимое ядро (Kotlin/JVM): домен, AI, память, синхронизация.
include(":core")

// :desktop — клиент для Windows (Compose Desktop) на том же ядре. Отключается свойством nova.skipDesktop=true.
if (providers.gradleProperty("nova.skipDesktop").orNull != "true") {
    include(":desktop")
}

// :app — Android-клиент. Можно отключить свойством nova.skipAndroid=true,
// чтобы собирать и тестировать ядро на машине без Android SDK.
val skipAndroid = providers.gradleProperty("nova.skipAndroid").orNull == "true"
if (!skipAndroid) {
    include(":app")
}
