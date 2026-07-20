package mindustry.yzf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** In-process Kotlin compiler used by YZF KT/KTS modules. */
public final class YZFEmbeddedKotlinRuntime implements AutoCloseable{
    private static final long COMPILE_TIMEOUT_SECONDS = 120L;
    private final ExecutorService compilerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MindustryYZF-KotlinCompiler");
        thread.setDaemon(true);
        return thread;
    });
    private URLClassLoader kotlinLoader;

    public CompiledModule compile(YZFModuleDefinition module) throws Exception{
        File cache = module.cacheDir.child("embedded-kotlin").file();
        if(!cache.exists() && !cache.mkdirs()) throw new IllegalStateException("Cannot create Kotlin cache directory: " + cache);
        String extension = module.mainScript.extension().toLowerCase();
        String entryClass = extension.equals("kts") ? resolveKtsEntryClass(module) : resolveEntryClass(module);
        File source = new File(cache, extension.equals("kts") ? "GeneratedKtsPlugin.kt" : entryClass + ".kt");
        File output = new File(cache, entryClass + "-" + System.nanoTime() + ".jar");
        String text = YZFText.readTextSmart(module.mainScript);
        if(extension.equals("kts")) text = prepareKts(text);
        Files.writeString(source.toPath(), text, StandardCharsets.UTF_8);

        List<String> args = new ArrayList<>();
        // The server JAR already supplies Kotlin stdlib/reflect through the
        // parent ClassLoader. Prevent the compiler from probing a nonexistent
        // standalone Kotlin distribution and do not duplicate stdlib in every
        // hot-reload artifact.
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-jvm-target");
        args.add("17");
        args.add("-classpath");
        args.add(System.getProperty("java.class.path", ""));
        args.add(source.getAbsolutePath());
        args.add("-d");
        args.add(output.getAbsolutePath());

        Future<CompileResult> future = compilerExecutor.submit(() -> runCompiler(args));
        try{
            CompileResult result = future.get(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if(result.code != 0) throw new IllegalStateException("Kotlin compilation failed for " + module.fullId() + ": " + result.output);
            return new CompiledModule(output, entryClass);
        }catch(TimeoutException timeout){
            future.cancel(true);
            output.delete();
            throw new IllegalStateException("Kotlin compilation timed out for " + module.fullId(), timeout);
        }
    }

    private CompileResult runCompiler(List<String> args){
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try{
            ClassLoader loader = kotlinClassLoader();
            Class<?> compilerType = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, loader);
            Object compiler = compilerType.getConstructor().newInstance();
            Method exec = compilerType.getMethod("exec", PrintStream.class, String[].class);
            Object code = exec.invoke(compiler, output, args.toArray(String[]::new));
            return new CompileResult(code != null && "OK".equals(code.toString()) ? 0 : 1, bytes.toString(StandardCharsets.UTF_8));
        }catch(Throwable error){
            if(error instanceof ClassNotFoundException || error.getCause() instanceof ClassNotFoundException){
                output.println("Embedded Kotlin compiler is unavailable. Put the Kotlin compiler/runtime jars in runtime-sdk/kotlin-libs.");
            }
            error.printStackTrace(output);
            return new CompileResult(1, bytes.toString(StandardCharsets.UTF_8));
        }finally{
            output.close();
        }
    }

    private synchronized ClassLoader kotlinClassLoader() throws Exception{
        YZFContext context = MindustryYZF.context();
        if(context == null) return getClass().getClassLoader();
        File root = new File(context.runtimeConfig.kotlinLibsPath);
        if(!root.isAbsolute()) root = context.paths.root.child(context.runtimeConfig.kotlinLibsPath).file();
        if(!root.isDirectory()) return getClass().getClassLoader();
        File[] files = root.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if(files == null || files.length == 0) return getClass().getClassLoader();
        if(kotlinLoader != null) return kotlinLoader;
        URL[] urls = new URL[files.length];
        for(int i = 0; i < files.length; i++) urls[i] = files[i].toURI().toURL();
        kotlinLoader = new URLClassLoader(urls, getClass().getClassLoader());
        return kotlinLoader;
    }

    private String resolveEntryClass(YZFModuleDefinition module){
        String name = module.mainScript.name();
        int dot = name.lastIndexOf('.');
        String simple = dot > 0 ? name.substring(0, dot).replaceAll("[^A-Za-z0-9_]", "_") : "GeneratedKotlinPlugin";
        String source = YZFText.readTextSmart(module.mainScript);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)").matcher(source);
        String prefix = matcher.find() ? matcher.group(1) + "." : "";
        java.util.regex.Matcher object = java.util.regex.Pattern.compile("(?m)^\\s*object\\s+([A-Za-z_][\\w]*)").matcher(source);
        if(object.find()) return prefix + object.group(1);
        java.util.regex.Matcher clazz = java.util.regex.Pattern.compile("(?m)^\\s*class\\s+([A-Za-z_][\\w]*)").matcher(source);
        if(clazz.find()) return prefix + clazz.group(1);
        return prefix + simple + "Kt";
    }

    private String wrapKts(String body){
        StringBuilder imports = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        for(String line : body.replace("\r\n", "\n").split("\n", -1)){
            if(line.trim().startsWith("import ")) imports.append(line).append('\n');
            else rest.append(line).append('\n');
        }
        return imports +
            "import mindustry.yzf.YZFEmbeddedRuntime\n" +
            "object GeneratedKtsPlugin {\n" +
            " @JvmStatic fun install(api: YZFEmbeddedRuntime.EmbeddedModuleApi) {\n" +
            "  val yzf = api\n" +
            "  val yzfModule = api.module()\n" +
            rest +
            " }\n" +
            "}\n";
    }

    private String prepareKts(String body){
        if(java.util.regex.Pattern.compile("(?m)^\\s*(public\\s+)?fun\\s+install\\s*\\(").matcher(body).find()){
            return "import mindustry.yzf.YZFEmbeddedRuntime\n" + body;
        }
        return wrapKts(body);
    }

    private String resolveKtsEntryClass(YZFModuleDefinition module){
        String body = YZFText.readTextSmart(module.mainScript);
        return java.util.regex.Pattern.compile("(?m)^\\s*(public\\s+)?fun\\s+install\\s*\\(").matcher(body).find()
            ? "GeneratedKtsPluginKt" : "GeneratedKtsPlugin";
    }

    @Override public void close(){
        compilerExecutor.shutdownNow();
        if(kotlinLoader != null){
            try{ kotlinLoader.close(); }catch(Exception ignored){}
            kotlinLoader = null;
        }
    }

    public record CompiledModule(File jar, String entryClass){}
    private record CompileResult(int code, String output){}
}
