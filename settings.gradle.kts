pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Pleos 공개 SDK 저장소. 차량용 빌드(-PpleosSdk=true)에서만 사용한다.
        if (providers.gradleProperty("pleosSdk").orNull == "true") maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            // 2026-11-14 TextToSpeech SDK 가 ai.pleos.playground.security 그룹(universal-access)을 함께 요구
            content { includeGroupByRegex("""ai\.pleos\.playground(\..*)?""") }
        }
    }
}
rootProject.name = "DropMate"
include(":app", ":engine")
