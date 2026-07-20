YZF Kotlin embedded dependency directory

Place the matching Kotlin 2.3.20 compiler/runtime jars here. The server keeps
the Kotlin compiler outside server.jar but loads it in-process through an
isolated dependency ClassLoader.

Required families:
- kotlin-compiler-embeddable
- kotlin-scripting-compiler-embeddable
- kotlin-scripting-common
- kotlin-scripting-jvm
- kotlin-scripting-jvm-host
- kotlin-stdlib
- their transitive Kotlin compiler dependencies

Set config/yzf/config/runtime.hjson:
  kotlin: { mode: "embedded-kotlin", libsPath: "runtime-sdk/kotlin-libs" }

This is not external-kotlin: no kotlinc process is started.
