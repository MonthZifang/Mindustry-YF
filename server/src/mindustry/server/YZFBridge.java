package mindustry.server;

import arc.files.Fi;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bridge between the main server jar and the YZF core module (mindustry.yzf).
 *
 * The core exists in two copies:
 * - internal: classes compiled into the main jar, used as fallback.
 * - external: the original .java sources released to config/yzf/core/src/mindustry/yzf/.
 *   They are compiled on demand with the JDK compiler and loaded through an isolated
 *   child-first class loader, so editing the released sources and running
 *   `yzfcore reload` applies changes without rebuilding the main jar.
 *
 * Loading modes (config/yzf/core.hjson, field "mode"):
 * - source (default): compile the released external sources and load them.
 * - internal: use the classes compiled into the main jar.
 *
 * The bridge only falls back to direct yzf references in internal mode; source mode
 * talks to the core purely through reflection plus the {@link ExternalAccess}
 * interface, which the core's config class implements.
 */
public final class YZFBridge{
    public static final String CORE_CLASS = "mindustry.yzf.MindustryYZF";

    private static final String CONFIG_NAME = "core.hjson";
    private static final String SOURCES_RESOURCE = "/yzf/yzf-core-sources.zip";
    private static final String SOURCES_DIR_NAME = "src";
    private static final String CORE_PACKAGE_PATH = "mindustry/yzf";
    private static final String CLASSES_DIR_NAME = "classes";

    /** Narrow view of the core's external access policy so the main jar needs no yzf types. */
    public interface ExternalAccess{
        boolean requiresToken(InetAddress address);
        boolean allows(InetAddress address, String presented);
        boolean allowsSocketBind(InetAddress address);
    }

    private static volatile String mode = "internal";
    private static volatile Fi sourceDir;
    private static volatile Fi classesDir;
    private static volatile ClassLoader coreLoader;
    private static volatile Class<?> coreClass;
    private static volatile String[] bootArgs;
    private static volatile ServerControl bootControl;
    private static volatile boolean bootstrapped;

    private YZFBridge(){
    }

    /** Boots the YZF core in the configured mode. Called once from {@link ServerLauncher}. */
    public static synchronized void bootstrap(String[] args, ServerControl control){
        if(bootstrapped) return;
        bootArgs = args;
        bootControl = control;

        Fi yzfRoot = Vars.dataDirectory.child("yzf");
        Fi coreDir = yzfRoot.child("core");
        coreDir.mkdirs();
        Fi configFile = yzfRoot.child(CONFIG_NAME);
        writeDefaultConfig(configFile);
        Jval config = readConfig(configFile);
        String configuredMode = config == null ? "source" : config.getString("mode", "source").trim().toLowerCase(Locale.ROOT);

        if(configuredMode.equals("source") || configuredMode.equals("external")){
            try{
                loadFromSource(coreDir);
                invokeBootstrap(args, control);
                mode = "source";
                bootstrapped = true;
                Log.info("[YZFBridge] 核心模块外加载完成（编译外部源码）: @", sourceDir.absolutePath());
                return;
            }catch(Throwable error){
                Log.err("[YZFBridge] 外加载失败，回退到内加载: @", error);
                closeLoader();
            }
        }
        mode = "internal";
        mindustry.yzf.MindustryYZF.bootstrap(args, control);
        bootstrapped = true;
        Log.info("[YZFBridge] 核心模块内加载完成（使用主 jar 内嵌类）。");
    }

    /** Current external access policy, or null before bootstrap / when unavailable. */
    public static ExternalAccess externalAccess(){
        if(mode.equals("source") && coreClass != null){
            try{
                Object raw = coreClass.getMethod("externalAccess").invoke(null);
                return raw instanceof ExternalAccess ea ? ea : null;
            }catch(Throwable error){
                return null;
            }
        }
        return mindustry.yzf.MindustryYZF.externalAccess();
    }

    /** Shuts the core down regardless of loading mode. Safe to call multiple times. */
    public static synchronized void shutdown(){
        try{
            if(mode.equals("source") && coreClass != null){
                coreClass.getMethod("shutdown").invoke(null);
            }else{
                mindustry.yzf.MindustryYZF.shutdown();
            }
        }catch(Throwable error){
            Log.err("[YZFBridge] 核心模块关闭失败: @", error);
        }finally{
            bootstrapped = false;
        }
    }

    /** Human-readable loading status for the `yzfcore` command. */
    public static String status(){
        StringBuilder builder = new StringBuilder();
        builder.append("加载模式: ").append(mode.equals("source") ? "外加载 (source，编译外部源码)" : "内加载 (internal)").append('\n');
        builder.append("核心源码目录: ").append(sourceDir == null ? "<未释放>" : sourceDir.absolutePath()).append('\n');
        builder.append("编译输出目录: ").append(classesDir == null ? "<无>" : classesDir.absolutePath()).append('\n');
        builder.append("类加载器: ").append(mode.equals("source") && coreLoader != null ? coreLoader : "主 jar (system)").append('\n');
        builder.append("引导状态: ").append(bootstrapped ? "已启动" : "未启动");
        return builder.toString();
    }

    /**
     * Hot-reloads the external core: shuts down whichever core copy is running,
     * recompiles the released sources (picking up any edits) and bootstraps again
     * with a fresh class loader. Works from both source and internal mode, so after
     * a fallback the user can fix the sources and switch back without restarting.
     */
    public static synchronized String reloadCore(){
        Fi coreDir = Vars.dataDirectory.child("yzf").child("core");
        shutdownCurrentCore();
        try{
            loadFromSource(coreDir);
            invokeBootstrap(bootArgs, bootControl);
            mode = "source";
            bootstrapped = true;
            return "核心模块已热重载（重新编译外部源码）: " + sourceDir.absolutePath();
        }catch(Throwable error){
            closeLoader();
            try{
                mindustry.yzf.MindustryYZF.bootstrap(bootArgs, bootControl);
                mode = "internal";
                bootstrapped = true;
                return "热重载失败，已回退到内加载。原因: " + error;
            }catch(Throwable fatal){
                bootstrapped = false;
                return "热重载失败且无法回退: " + fatal;
            }
        }
    }

    /** Registers the main-jar-side `yzfcore` management command. */
    public static void registerCommands(CommandHandler handler){
        handler.register("yzfcore", "[status|reload|reset]", "管理 YZF 核心模块：status 查看加载状态，reload 重新编译外部源码并热重载，reset 把外部源码还原为主 jar 内置版本并重载。", args -> {
            String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch(action){
                case "status", "状态" -> Log.info(status());
                case "reload", "热重载" -> Log.info(reloadCore());
                case "reset", "还原" -> Log.info(resetCore());
                default -> Log.err("用法: yzfcore [status|reload|reset]");
            }
        });
    }

    /**
     * Restores the external core sources to the pristine copy embedded in the main jar,
     * deletes stale compiled output and hot-reloads. Lets arbitrary local edits be
     * reverted without touching the server build. Works from both loading modes.
     */
    public static synchronized String resetCore(){
        Fi coreDir = Vars.dataDirectory.child("yzf").child("core");
        Fi srcDir = coreDir.child(SOURCES_DIR_NAME);
        Fi outputDir = coreDir.child(CLASSES_DIR_NAME);

        shutdownCurrentCore();

        try{
            if(srcDir.exists()) srcDir.deleteDirectory();
            if(outputDir.exists()) outputDir.deleteDirectory();
            if(!extractSources(srcDir)){
                throw new IllegalStateException("主 jar 中未找到内嵌核心源码包，无法还原。");
            }
            loadFromSource(coreDir);
            invokeBootstrap(bootArgs, bootControl);
            mode = "source";
            bootstrapped = true;
            return "外部核心源码已还原为主 jar 内置版本并重新加载: " + sourceDir.absolutePath();
        }catch(Throwable error){
            closeLoader();
            try{
                mindustry.yzf.MindustryYZF.bootstrap(bootArgs, bootControl);
                mode = "internal";
                bootstrapped = true;
                return "还原失败，已回退到内加载。原因: " + error;
            }catch(Throwable fatal){
                bootstrapped = false;
                return "还原失败且无法回退: " + fatal;
            }
        }
    }

    private static void shutdownCurrentCore(){
        try{
            if(mode.equals("source") && coreClass != null){
                coreClass.getMethod("shutdown").invoke(null);
            }else{
                mindustry.yzf.MindustryYZF.shutdown();
            }
        }catch(Throwable error){
            Log.err("[YZFBridge] 旧核心关闭失败（继续操作）: @", error);
        }finally{
            closeLoader();
            bootstrapped = false;
        }
    }

    /**
     * Dependency-free smart text reader used by console configuration loading, which
     * happens before (and without) the core module. Tries strict UTF-8 first, then
     * legacy Chinese encodings, mirroring the core's YZFText behavior for config files.
     */
    public static String readTextSmart(Fi file){
        if(file == null || !file.exists()) return "";
        byte[] bytes = file.readBytes();
        if(bytes.length == 0) return "";
        if(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF){
            byte[] trimmed = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        String strict = decodeStrict(bytes, StandardCharsets.UTF_8);
        if(strict != null) return strict;
        try{
            return new String(bytes, Charset.forName("GB18030"));
        }catch(Throwable error){
            return new String(bytes, Charset.defaultCharset());
        }
    }

    // --- internals ---

    private static void loadFromSource(Fi coreDir) throws Exception{
        Fi srcDir = coreDir.child(SOURCES_DIR_NAME);
        Fi coreSources = srcDir.child(CORE_PACKAGE_PATH);
        if(!coreSources.exists() || coreSources.list().length == 0){
            if(!extractSources(srcDir)){
                throw new IllegalStateException("主 jar 中未找到内嵌核心源码包，无法释放外部源码。请用打包好的 server-release.jar 运行。");
            }
            Log.info("[YZFBridge] 已释放核心模块源码: @", coreSources.absolutePath());
        }

        List<File> sources = new ArrayList<>();
        collectJavaFiles(srcDir.file(), sources);
        if(sources.isEmpty()){
            throw new IllegalStateException("外部核心源码为空: " + srcDir.absolutePath());
        }

        Fi outputDir = coreDir.child(CLASSES_DIR_NAME);
        outputDir.mkdirs();
        long start = System.currentTimeMillis();
        compileSources(sources, outputDir.file());
        Log.info("[YZFBridge] 外部核心源码编译完成: @ 个文件，耗时 @ ms，输出 @", sources.size(), System.currentTimeMillis() - start, outputDir.absolutePath());

        sourceDir = coreSources;
        classesDir = outputDir;
        coreLoader = new CoreClassLoader(new URL[]{outputDir.file().toURI().toURL()}, YZFBridge.class.getClassLoader());
        coreClass = Class.forName(CORE_CLASS, true, coreLoader);
    }

    private static void compileSources(List<File> sources, File outputDir) throws Exception{
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if(compiler == null){
            throw new IllegalStateException("JDK 编译器不可用，请使用完整 JDK 运行服务端以编译外部核心源码。");
        }
        try(StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)){
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path", "."),
                "-encoding", "UTF-8"
            );
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            Boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjectsFromFiles(sources)).call();
            if(!Boolean.TRUE.equals(ok)){
                StringBuilder message = new StringBuilder("外部核心源码编译失败:");
                int shown = 0;
                for(Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()){
                    if(diagnostic.getKind() != Diagnostic.Kind.ERROR) continue;
                    message.append("\n  ").append(diagnostic);
                    if(++shown >= 10){
                        message.append("\n  ...（更多错误省略）");
                        break;
                    }
                }
                throw new IllegalStateException(message.toString());
            }
        }
    }

    private static void collectJavaFiles(File dir, List<File> out){
        File[] children = dir.listFiles();
        if(children == null) return;
        for(File child : children){
            if(child.isDirectory()){
                collectJavaFiles(child, out);
            }else if(child.getName().toLowerCase(Locale.ROOT).endsWith(".java")){
                out.add(child);
            }
        }
    }

    private static void invokeBootstrap(String[] args, ServerControl control) throws Exception{
        Method bootstrap = coreClass.getMethod("bootstrap", String[].class, ServerControl.class);
        bootstrap.invoke(null, args, control);
    }

    private static void closeLoader(){
        if(coreLoader instanceof URLClassLoader closeable){
            try{
                closeable.close();
            }catch(Throwable ignored){
            }
        }
        coreLoader = null;
        coreClass = null;
    }

    private static void writeDefaultConfig(Fi file){
        if(file.exists()) return;
        file.writeString(
            "# YZF 核心模块加载配置。\n" +
            "# mode: source = 外加载，编译并加载 core/src 下的原始源码（默认）；\n" +
            "#       internal = 内加载，直接使用主 jar 内嵌的核心类。\n" +
            "# 说明: 首次启动会把主 jar 内嵌的核心源码原封不动释放到 core/src/mindustry/yzf/，\n" +
            "# 之后启动不会覆盖已有源码；直接修改这些 .java 文件后执行 `yzfcore reload`，\n" +
            "# 服务端会重新编译并热重载核心模块（仅 source 模式）。\n" +
            "# 编译输出在 core/classes/，可随时删除让其全量重建。\n" +
            "mode: source\n"
        );
    }

    private static Jval readConfig(Fi file){
        try{
            return Jval.read(file.readString());
        }catch(Throwable error){
            Log.warn("[YZFBridge] 核心加载配置无效，使用默认值: @", error);
            return null;
        }
    }

    private static boolean extractSources(Fi targetDir){
        try(InputStream in = YZFBridge.class.getResourceAsStream(SOURCES_RESOURCE)){
            if(in == null) return false;
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            while((entry = zip.getNextEntry()) != null){
                if(entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if(name.isEmpty() || name.contains("..")) continue;
                Fi out = targetDir.child(name);
                out.parent().mkdirs();
                try(OutputStream os = out.write(false)){
                    zip.transferTo(os);
                }
                zip.closeEntry();
            }
            return true;
        }catch(Throwable error){
            Log.err("[YZFBridge] 释放核心源码失败: @", error);
            return false;
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset){
        try{
            CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        }catch(CharacterCodingException error){
            return null;
        }
    }

    /** Child-first loader: mindustry.yzf.* comes from the compiled external sources, everything else from the main jar. */
    private static final class CoreClassLoader extends URLClassLoader{
        CoreClassLoader(URL[] urls, ClassLoader parent){
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
            if(name.startsWith("mindustry.yzf.")){
                synchronized(getClassLoadingLock(name)){
                    Class<?> loaded = findLoadedClass(name);
                    if(loaded == null){
                        try{
                            loaded = findClass(name);
                        }catch(ClassNotFoundException error){
                            return super.loadClass(name, resolve);
                        }
                    }
                    if(resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
