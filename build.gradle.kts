plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// Optional build root for local CI; a normal checkout uses Gradle's standard build directories.
systemEnvironmentBuildRoot()?.let { output ->
    allprojects { layout.buildDirectory.set(file("$output/${project.name}")) }
}
fun systemEnvironmentBuildRoot(): String? = System.getenv("FL_BUILD_ROOT")?.takeIf { it.isNotBlank() }
