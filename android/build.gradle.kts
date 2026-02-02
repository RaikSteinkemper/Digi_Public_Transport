// Top-level build file for Android app
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

task("clean") {
    delete(rootProject.buildDir)
}
