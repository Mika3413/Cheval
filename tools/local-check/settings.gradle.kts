// Build de vérification hors-ligne (sans Android Gradle Plugin) :
// compile le cœur + le code Android contre android.jar et lance les tests.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.0.21" }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "cheval-local-check"
include(":core")
project(":core").projectDir = file("../../core")
include(":appcheck")
include(":shots")
