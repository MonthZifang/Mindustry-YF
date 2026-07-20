plugins {
    kotlin("jvm") version "2.3.20"
}

group = "yzf.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Compile against the server API; the server supplies Mindustry and YZF classes.
    compileOnly(files("../build/libs/server-release.jar"))
    implementation(kotlin("stdlib"))
}

tasks.jar {
    archiveFileName.set("kotlin-runtime-test.jar")
    manifest {
        attributes["Main-Class"] = "example.KotlinRuntimeTest"
    }
    from(configurations.runtimeClasspath.get().map { if(it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
