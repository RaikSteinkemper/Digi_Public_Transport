// Top-level build file for Android app
plugins {
    id("com.android.application") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

task("clean") {
    delete(rootProject.buildDir)
}
