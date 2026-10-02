import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Captures d'écran hors appareil : Robolectric en mode graphique natif.
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

sourceSets {
    test {
        kotlin.srcDir("../../../core/src/main/kotlin")
        kotlin.srcDir("../../../app/src/main/java")
    }
}

val roboRuntime by configurations.creating

dependencies {
    roboRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") { isTransitive = false }
    testImplementation("junit:junit:4.13.2")
    // androidx.test vient de maven.google.com (inaccessible ici) : on s'en passe.
    testImplementation("org.robolectric:robolectric:4.14.1") { exclude(group = "androidx.test"); exclude(group = "androidx.test.espresso") }
    testImplementation("org.robolectric:android-all:14-robolectric-10818077")
}

val roboDir = layout.buildDirectory.dir("robolectric-deps")
val copyRobo by tasks.registering(Copy::class) {
    from(roboRuntime)
    into(roboDir)
}

tasks.test {
    dependsOn(copyRobo)
    // Robolectric télécharge sinon lui-même le framework (souvent limité en débit) : mode hors-ligne.
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", roboDir.get().asFile.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("shots.dir", rootProject.file("out/shots").absolutePath)
    maxHeapSize = "2g"
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
