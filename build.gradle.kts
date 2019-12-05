// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        jcenter()
        google()
        maven(url = "https://maven.fabric.io/public")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:3.5.2")
        classpath("com.google.gms:google-services:4.3.3")
        classpath(kotlin("gradle-plugin", version = "1.3.61"))
        classpath("io.fabric.tools:gradle:1.31.2")
        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        jcenter()
        maven(url = "https://jitpack.io")
        google()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}