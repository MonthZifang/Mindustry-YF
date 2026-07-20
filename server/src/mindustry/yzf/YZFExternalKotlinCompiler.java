package mindustry.yzf;

import arc.files.Fi;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class YZFExternalKotlinCompiler{
    private static final long COMPILE_TIMEOUT_SECONDS = 120L;

    private YZFExternalKotlinCompiler(){
    }

    public static File compile(YZFModuleDefinition module) throws Exception{
        String compiler = MindustryYZF.context().runtimeConfig.resolveCompiler(MindustryYZF.context().paths);
        if(compiler == null) compiler = resolveFromPath();

        File source = module.cacheDir.child("external-kotlin").child("GeneratedKtsPlugin.kt").file();
        // Never overwrite the artifact used by the previous URLClassLoader.
        // This is required on Windows, where an open JarFile can keep the
        // path locked during a hot reload.
        File output = module.cacheDir.child("external-kotlin")
            .child(module.id() + "-runtime-" + System.nanoTime() + ".jar").file();
        File directory = source.getParentFile();
        if(!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create Kotlin cache directory: " + directory);

        String body = YZFText.readTextSmart(module.mainScript);
        String sourceText = "import mindustry.yzf.YZFEmbeddedRuntime\n\n" +
            "object GeneratedKtsPlugin {\n" +
            "    @JvmStatic fun install(api: YZFEmbeddedRuntime.EmbeddedModuleApi) {\n" +
            "        val yzf = api\n" +
            "        val yzfModule = api.module()\n" +
            body + "\n" +
            "    }\n" +
            "}\n";
        Files.writeString(source.toPath(), sourceText, StandardCharsets.UTF_8);

        File serverJar = resolveCodeSource();
        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add(source.getAbsolutePath());
        command.add("-classpath");
        command.add(serverJar.getAbsolutePath());
        command.add("-include-runtime");
        command.add("-jvm-target");
        command.add("17");
        command.add("-d");
        command.add(output.getAbsolutePath());
        Process process = new ProcessBuilder(command)
            .directory(module.root.file())
            .redirectErrorStream(true)
            .start();

        ByteArrayOutputStream compilerOutput = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> {
            try{
                process.getInputStream().transferTo(compilerOutput);
            }catch(IOException ignored){
                // The process may close its pipe while being terminated.
            }
        }, "MindustryYZF-KotlinCompilerOutput");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if(!finished){
            process.destroy();
            if(!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            outputReader.join(5000L);
            throw new IllegalStateException("External Kotlin compilation timed out for " + module.fullId());
        }
        outputReader.join(5000L);
        String outputText = compilerOutput.toString(StandardCharsets.UTF_8);
        int code = process.exitValue();
        if(code != 0){
            throw new IllegalStateException("External Kotlin compilation failed for " + module.fullId() + ": " + outputText);
        }
        return output;
    }

    private static File resolveCodeSource(){
        try{
            return new File(YZFExternalKotlinCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        }catch(Exception e){
            throw new IllegalStateException("Cannot locate server jar for Kotlin compilation", e);
        }
    }

    private static String resolveFromPath(){
        String path = System.getenv("PATH");
        if(path != null){
            for(String entry : path.split(File.pathSeparator)){
                File bat = new File(entry, "kotlinc.bat");
                if(bat.isFile()) return bat.getAbsolutePath();
                File shell = new File(entry, "kotlinc");
                if(shell.isFile()) return shell.getAbsolutePath();
            }
        }
        String home = System.getenv("KOTLIN_HOME");
        if(home != null){
            File bat = new File(home, "bin/kotlinc.bat");
            if(bat.isFile()) return bat.getAbsolutePath();
            File shell = new File(home, "bin/kotlinc");
            if(shell.isFile()) return shell.getAbsolutePath();
        }
        throw new IllegalStateException("External Kotlin mode is enabled, but kotlinc was not found. Set compilerPath in runtime.hjson.");
    }
}
