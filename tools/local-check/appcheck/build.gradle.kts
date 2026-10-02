import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

val androidJar = providers.environmentVariable("ANDROID_JAR")
    .orElse("/usr/lib/android-sdk/platforms/android-23/android.jar")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Bytecode compatible avec l'ancien dexer "dx" utilisé pour la vérification locale.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
    }
}

sourceSets {
    main {
        kotlin.srcDir("../../../core/src/main/kotlin")
        kotlin.srcDir("../../../app/src/main/java")
    }
}

dependencies {
    compileOnly(files(androidJar))
}
