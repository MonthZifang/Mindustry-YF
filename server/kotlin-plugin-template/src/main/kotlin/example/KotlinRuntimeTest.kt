package example

import mindustry.yzf.YZFEmbeddedRuntime
import java.io.File

object KotlinRuntimeTest {
    @JvmStatic
    fun install(api: YZFEmbeddedRuntime.EmbeddedModuleApi) {
        val marker = File(api.module().dataPath, "kotlin-jar-runtime.ok")
        marker.parentFile.mkdirs()
        marker.writeText("Precompiled Kotlin plugin executed for ${api.module().fullId}\n")
        api.info("[KOTLIN-JAR-TEST] precompiled Kotlin plugin loaded: ${api.module().fullId}")
    }
}
