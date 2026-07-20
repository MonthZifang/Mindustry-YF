# YZF Kotlin Plugin

This template compiles Kotlin during plugin development. The server only loads the resulting jar and does not contain a Kotlin compiler.

Build with:

```text
gradlew.bat jar
```

Copy `build/libs/kotlin-runtime-test.jar` into a plugin directory and use this module metadata:

```hjson
id: "kotlin-runtime-test"
name: "Kotlin Runtime Test"
author: "your-name"
main: "kotlin-runtime-test.jar"
runtime: "java"
enabled: true
loadType: "plugin"
```
