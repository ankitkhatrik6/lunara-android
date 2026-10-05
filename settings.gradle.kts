pluginManagement {
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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = java.net.URI("https://jitpack.io") }
  }
}

rootProject.name = "Lunara"

// Two modules, split along the line that actually matters here:
//
//   :extractor  LunaraExtractor - the stream-extraction engine. Client registry,
//               BotGuard PO-token minting, stream resolution and validation. Nothing
//               else in the app knows how a googlevideo URL is obtained, which is
//               what lets it be replaced without touching playback or UI.
//   :app        Compose UI, Media3 playback service, InnerTube catalogue, lyrics,
//               Room persistence.
//
// A broken extractor is the failure mode that makes a music player look dead rather
// than broken, so it is isolated where it can be read, reasoned about and fixed on
// its own.
include(":app")
include(":extractor")
