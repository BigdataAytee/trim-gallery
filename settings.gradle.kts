pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "trim-gallery"

// ARCHITECTURE.md § 3. One-way dependency flow:
//   app -> feature -> core -> engine-api <- engine-impl(platform)
include(":shared:engine-api")
include(":shared:core:model")
include(":shared:core:domain")
include(":shared:core:data")
include(":shared:core:pipeline")
include(":shared:core:ui")

include(":shared:feature:space")
include(":shared:feature:compress")
include(":shared:feature:settings")

include(":androidApp")
include(":benchmark")

// iosApp is an Xcode project, not a Gradle module. It is present but not implemented;
// see iosApp/README.md. It will consume shared/ as an XCFramework.
