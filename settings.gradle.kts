pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Pleos Connect SDK(2026-10-21): 이 저장소에서는 Pleos 꾸러미만 받는다(다른 라이브러리를 대신 내려주지 못하게).
        exclusiveContent {
            forRepository { maven("https://nexus-playground.pleos.ai/repository/maven-releases/") }
            filter { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}
rootProject.name = "VoyadioAutomotive"
include(":app")
