package mindustry.yzf;

import arc.Events;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.Liquid;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static mindustry.Vars.content;
import static mindustry.Vars.logic;
import static mindustry.Vars.netServer;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

public final class YZFEmbeddedRuntime{
    private final ObjectMap<String, EmbeddedModuleState> modules = new ObjectMap<>();
    private final YZFEmbeddedKotlinRuntime kotlinRuntime = new YZFEmbeddedKotlinRuntime();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, EmbeddedExport>> embeddedExports = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, EmbeddedCallableCommand> embeddedCommands = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteIds> moduleCommands = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteIds> moduleWsIds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, EmbeddedWsConnection> wsConnections = new ConcurrentHashMap<>();
    private static final AtomicInteger nextWsId = new AtomicInteger(1);
    private static final int maxWebSocketMessageChars = 1024 * 1024;
    private static final int maxEmbeddedWebSocketConnections = 128;
    private static ExecutorService wsExecutor;

    public synchronized void reload(YZFModuleDefinition module){
        if(module.meta.runtime == null) return;
        String runtime = normalizeRuntime(module.meta.runtime);
        if(!(runtime.equals("java") || runtime.equals("kt") || runtime.equals("kts"))) return;
        if(!module.hasMain()){
            throw new IllegalStateException("Missing module main file: " + module.fullId());
        }

        YZFEmbeddedKotlinRuntime.CompiledModule preparedKotlin = null;
        try{
            if(runtime.equals("kt") || runtime.equals("kts")){
                // Compile before stopping the old module so a syntax/compile error keeps the old version alive.
                preparedKotlin = kotlinRuntime.compile(module);
            }
        }catch(Throwable error){
            throw new IllegalStateException("Failed to prepare Kotlin module " + module.fullId(), error);
        }

        stop(module.fullId());

        EmbeddedModuleState state = new EmbeddedModuleState(module, runtime);
        try{
            module.cacheDir.mkdirs();
            if(runtime.equals("java")) loadJavaModule(state);
            else loadKotlinModule(state, preparedKotlin);
            modules.put(module.fullId(), state);
            state.runEnable();
            MindustryYZF.context().audit.record("module-start", module.fullId(), runtime + "-embedded");
            Log.info("[@] Embedded module loaded: @ (@)", MindustryYZF.name, module.fullId(), runtime);
        }catch(Throwable t){
            cleanupState(state, false);
            throw new IllegalStateException("Failed to load embedded module " + module.fullId() + ": " + t.getMessage(), t);
        }
    }

    public synchronized void reloadCompiledJar(YZFModuleDefinition module, File jarFile){
        // Validate the compiler artifact before touching the currently running
        // module. External Kotlin always emits this stable entry object; a
        // broken/partial jar must leave the previous version active.
        validateCompiledKotlinJar(jarFile, module.fullId());
        stop(module.fullId());
        EmbeddedModuleState state = new EmbeddedModuleState(module, "java");
        try{
            module.cacheDir.mkdirs();
            loadJavaJar(state, jarFile, "GeneratedKtsPlugin");
            modules.put(module.fullId(), state);
            state.runEnable();
            MindustryYZF.context().audit.record("module-start", module.fullId(), "external-kotlin");
            Log.info("[@] External Kotlin module switched: @", MindustryYZF.name, module.fullId());
        }catch(Throwable t){
            cleanupState(state, false);
            throw new IllegalStateException("Failed to load compiled Kotlin module " + module.fullId() + ": " + t.getMessage(), t);
        }
    }

    private void validateCompiledKotlinJar(File jarFile, String moduleId){
        if(jarFile == null || !jarFile.isFile() || jarFile.length() == 0){
            throw new IllegalStateException("Kotlin compiler did not produce a readable jar for " + moduleId);
        }
        try(JarFile jar = new JarFile(jarFile)){
            if(jar.getJarEntry("GeneratedKtsPlugin.class") == null){
                throw new IllegalStateException("Compiled Kotlin jar has no GeneratedKtsPlugin entry for " + moduleId);
            }
        }catch(IOException error){
            throw new IllegalStateException("Cannot validate compiled Kotlin jar for " + moduleId, error);
        }
    }

    public synchronized void stop(String moduleId){
        EmbeddedModuleState state = modules.remove(moduleId);
        if(state == null) return;
        cleanupState(state, true);
        YZFContext context = MindustryYZF.context();
        if(context != null) context.audit.record("module-stop", moduleId, state.runtime + "-embedded");
    }

    public synchronized void stopAll(){
        for(String id : modules.keys().toSeq()){
            stop(id);
        }
        shutdownWebSockets();
        kotlinRuntime.close();
    }

    public synchronized Seq<String> moduleIds(){
        return modules.keys().toSeq();
    }

    public synchronized int size(){
        return modules.size;
    }

    public synchronized java.util.List<java.util.Map<String, Object>> snapshot(){
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for(EmbeddedModuleState state : modules.values()){
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("moduleId", state.definition.fullId());
            item.put("runtime", state.runtime);
            item.put("active", true);
            item.put("classLoaderIsolation", state.classLoader != null);
            item.put("classLoaderClosed", false);
            item.put("mainScript", state.definition.mainScript.absolutePath());
            item.put("metaFile", state.definition.metaFile.absolutePath());
            item.put("sourceBytes", state.definition.mainScript.file().length());
            item.put("sourceText", YZFText.readTextSmart(state.definition.mainScript));
            item.put("memoryMin", state.definition.meta.memoryMin);
            item.put("memoryMax", state.definition.meta.memoryMax);
            item.put("memoryLimitEnforced", false);
            item.put("commands", state.serverCommands.size);
            item.put("playerCommands", state.playerCommands.size);
            item.put("events", state.eventBindings.size);
            item.put("tasks", state.taskBindings.size);
            result.add(item);
        }
        return result;
    }

    public synchronized YZFModuleDefinition definition(String moduleId){
        EmbeddedModuleState state = modules.get(moduleId);
        return state == null ? null : state.definition;
    }

    private void loadKotlinModule(EmbeddedModuleState state, YZFEmbeddedKotlinRuntime.CompiledModule prepared) throws Exception{
        YZFEmbeddedKotlinRuntime.CompiledModule compiled = prepared == null ? kotlinRuntime.compile(state.definition) : prepared;
        loadJavaJar(state, compiled.jar(), compiled.entryClass());
    }

    private List<File> resolveKotlinScriptClasspath(EmbeddedModuleState state) throws IOException{
        LinkedHashSet<File> entries = new LinkedHashSet<>();
        String classpath = System.getProperty("java.class.path", "");
        if(!YZFText.blank(classpath)){
            for(String raw : classpath.split(java.io.File.pathSeparator)){
                if(YZFText.blank(raw)) continue;
                File entry = new File(raw);
                if(!entry.exists()) continue;
                entries.add(normalizeClasspathEntry(state, entry));
            }
        }

        if(entries.isEmpty()){
            File codeSource = resolveCodeSourceFile();
            if(codeSource != null && codeSource.exists()){
                entries.add(normalizeClasspathEntry(state, codeSource));
            }
        }
        return new ArrayList<>(entries);
    }

    private File resolveCodeSourceFile(){
        try{
            URI uri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            return new File(uri);
        }catch(Exception ignored){
            return null;
        }
    }

    private File normalizeClasspathEntry(EmbeddedModuleState state, File entry) throws IOException{
        if(entry.isDirectory()) return entry.getCanonicalFile();
        String name = entry.getName().toLowerCase();
        if(!name.endsWith(".jar")) return entry.getCanonicalFile();
        return explodeClasspathJar(state, entry);
    }

    private File explodeClasspathJar(EmbeddedModuleState state, File jarFile) throws IOException{
        File outputDir = state.definition.cacheDir.child("kotlin-classpath").child(safeCacheName(jarFile)).file();
        File marker = new File(outputDir, ".yzf-extracted");
        if(marker.exists()){
            return outputDir.getCanonicalFile();
        }
        if(!outputDir.exists() && !outputDir.mkdirs()){
            throw new IOException("Failed to create Kotlin classpath cache directory: " + outputDir.getAbsolutePath());
        }

        try(JarFile jar = new JarFile(jarFile)){
            Enumeration<JarEntry> entries = jar.entries();
            while(entries.hasMoreElements()){
                JarEntry jarEntry = entries.nextElement();
                if(jarEntry.isDirectory()) continue;
                String entryName = jarEntry.getName();
                // 跳过多版本 jar 的 module-info，避免污染 Kotlin 脚本 classpath
                if(entryName.startsWith("META-INF/versions/")) continue;
                // 关键：根 module-info.class 会让解压目录变成 JPMS 模块，
                // 导致 Kotlin 脚本引擎把 arc/mindustry/kotlin 等符号判为
                // "声明在某个未导出该包的模块里"（如 com.zaxxer.hikari），
                // 最终所有 import 全部编译失败。必须剔除所有层级的 module-info.class。
                if(entryName.equals("module-info.class")){
                    continue;
                }
                File target = new File(outputDir, entryName);
                File parent = target.getParentFile();
                if(parent != null && !parent.exists() && !parent.mkdirs()){
                    throw new IOException("Failed to create directory for Kotlin classpath extraction: " + parent.getAbsolutePath());
                }
                Files.copy(jar.getInputStream(jarEntry), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if(!marker.exists() && !marker.createNewFile()){
            throw new IOException("Failed to finalize Kotlin classpath cache: " + marker.getAbsolutePath());
        }
        return outputDir.getCanonicalFile();
    }

    private String safeCacheName(File jarFile){
        String name = jarFile.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        return name + "-" + Long.toHexString(jarFile.lastModified()) + "-" + Long.toHexString(jarFile.length());
    }

    private URL[] toUrls(List<File> files) throws IOException{
        URL[] urls = new URL[files.size()];
        for(int i = 0; i < files.size(); i++){
            urls[i] = files.get(i).getCanonicalFile().toURI().toURL();
        }
        return urls;
    }

    private String joinClasspath(List<File> files) throws IOException{
        List<String> parts = new ArrayList<>(files.size());
        for(File file : files){
            parts.add(file.getCanonicalPath());
        }
        return String.join(File.pathSeparator, parts);
    }

    private void loadJavaModule(EmbeddedModuleState state) throws Exception{
        String extension = state.definition.mainScript.extension().toLowerCase();
        if(extension.equals("jar")){
            loadJavaJar(state, state.definition.mainScript.file());
            return;
        }
        if(!extension.equals("java")){
            throw new IllegalStateException("Unsupported embedded java entry file: " + state.definition.mainScript.absolutePath());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if(compiler == null){
            throw new IllegalStateException("JDK compiler is not available. Use a full JDK to run .java modules in-process.");
        }

        File outputDir = state.definition.cacheDir.child("java-classes").file();
        if(!outputDir.exists() && !outputDir.mkdirs()){
            throw new IOException("Failed to create cache directory: " + outputDir.getAbsolutePath());
        }

        File sourceFile = prepareJavaSource(state);

        try(StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)){
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile);
            List<String> options = new ArrayList<>();
            options.add("-classpath");
            options.add(System.getProperty("java.class.path"));
            Boolean ok = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call();
            if(!Boolean.TRUE.equals(ok)){
                throw new IllegalStateException("Java source compilation failed for " + state.definition.fullId());
            }
        }

        state.classLoader = new URLClassLoader(new URL[]{outputDir.toURI().toURL()}, getClass().getClassLoader());
        String className = stripExtension(state.definition.mainScript.name());
        invokeJavaEntry(state, className);
    }

    private File prepareJavaSource(EmbeddedModuleState state) throws IOException{
        String source = YZFText.readTextSmart(state.definition.mainScript);
        if(!source.isEmpty() && source.charAt(0) == '\ufeff'){
            source = source.substring(1);
        }

        File sourceDir = state.definition.cacheDir.child("java-source").file();
        if(!sourceDir.exists() && !sourceDir.mkdirs()){
            throw new IOException("Failed to create Java source cache directory: " + sourceDir.getAbsolutePath());
        }

        File sourceFile = new File(sourceDir, state.definition.mainScript.name());
        Files.writeString(sourceFile.toPath(), source, StandardCharsets.UTF_8);
        return sourceFile;
    }

    private void loadJavaJar(EmbeddedModuleState state, File jarFile) throws Exception{
        loadJavaJar(state, jarFile, null);
    }

    private void loadJavaJar(EmbeddedModuleState state, File jarFile, String explicitMain) throws Exception{
        state.classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, getClass().getClassLoader());
        String manifestMain;
        try(java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)){
            manifestMain = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if(YZFText.blank(manifestMain) && YZFText.blank(explicitMain)){
            throw new IllegalStateException("Jar module has no Main-Class manifest entry: " + jarFile.getAbsolutePath());
        }
        invokeJavaEntry(state, YZFText.blank(explicitMain) ? manifestMain : explicitMain);
    }

    private void invokeJavaEntry(EmbeddedModuleState state, String className) throws Exception{
        ClassLoader loader = Objects.requireNonNull(state.classLoader, "classLoader");
        Class<?> type = Class.forName(className, true, loader);

        try{
            Method install = type.getMethod("install", EmbeddedModuleApi.class);
            install.invoke(entryReceiver(type, install), new JavaModuleApi(state));
            return;
        }catch(NoSuchMethodException ignored){
        }

        try{
            Method install = type.getMethod("install", JavaModuleApi.class);
            install.invoke(entryReceiver(type, install), new JavaModuleApi(state));
            return;
        }catch(NoSuchMethodException ignored){
        }

        try{
            Method main = type.getMethod("main", String[].class);
            main.invoke(entryReceiver(type, main), (Object)state.definition.meta.programArgs.toArray(String.class));
            return;
        }catch(NoSuchMethodException ignored){
        }

        throw new IllegalStateException("Embedded java module must define install(EmbeddedModuleApi) or main(String[]) in " + className);
    }

    private Object entryReceiver(Class<?> type, Method method) throws Exception{
        if(Modifier.isStatic(method.getModifiers())) return null;

        // Kotlin object declarations expose their singleton as INSTANCE.
        try{
            return type.getField("INSTANCE").get(null);
        }catch(NoSuchFieldException ignored){
            // A normal Kotlin/Java class may still provide a public no-arg entry.
            var constructor = type.getDeclaredConstructor();
            if(!constructor.canAccess(null)) constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private void cleanupState(EmbeddedModuleState state, boolean runDisable){
        if(runDisable){
            try{
                state.runDisable();
            }catch(Throwable t){
                YZFErrorLog.high(state.definition.fullId(), "Embedded module disable failed", t);
            }
        }

        YZFContext context = MindustryYZF.context();
        CommandHandler handler = context == null || context.serverControl == null ? null : context.serverControl.handler;
        if(handler != null) for(String command : state.serverCommands){
            handler.removeCommand(command);
        }
        if(Vars.netServer != null) for(YZFPlayerCommandBinding binding : state.playerCommands){
            Vars.netServer.clientCommands.removeCommand(binding.name);
        }
        for(YZFEventBinding binding : state.eventBindings){
            Events.remove((Class)binding.eventType, (Cons)binding.handler);
        }
        for(YZFTaskBinding binding : state.taskBindings){
            binding.cancel();
        }
        state.serverCommands.clear();
        state.playerCommands.clear();
        state.eventBindings.clear();
        state.taskBindings.clear();

        if(context != null) context.webUi.unregisterModule(state.definition.fullId());

        embeddedExports.remove(state.definition.fullId());
        CopyOnWriteIds commandIds = moduleCommands.remove(state.definition.fullId());
        if(commandIds != null){
            for(String id : commandIds.ids){
                embeddedCommands.remove(id);
            }
        }
        CopyOnWriteIds wsIds = moduleWsIds.remove(state.definition.fullId());
        if(wsIds != null){
            for(String id : wsIds.ids){
                wsClose(id);
            }
        }

        URLClassLoader loader = state.classLoader;
        if(loader != null){
            try{
                loader.close();
            }catch(IOException ignored){
            }
        }
    }

    private String normalizeRuntime(String runtime){
        if(runtime == null) return "";
        String value = runtime.trim().toLowerCase();
        return value.equals("kts") ? "kt" : value;
    }

    private String stripExtension(String fileName){
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(0, index) : fileName;
    }

    public interface EmbeddedModuleApi{
        void onEnable(Runnable callback);

        void onDisable(Runnable callback);

        void command(String name, String usage, String description, Consumer<String[]> handler);

        void playerCommand(String name, String usage, String description, BiConsumer<Player, String[]> handler);

        void adminCommand(String name, String usage, String description, String permission, BiConsumer<Player, String[]> handler);

        void after(float delaySeconds, Runnable callback);

        void every(float delaySeconds, float intervalSeconds, Runnable callback);

        void on(String eventName, Consumer<String> handler);

        String configGet(String key, String defaultValue);

        boolean configGetBool(String key, boolean defaultValue);

        int configGetInt(String key, int defaultValue);

        void configSet(String key, String value);

        void configSetBool(String key, boolean value);

        void configSetInt(String key, int value);

        String configPath();

        String serviceCall(String serviceId, String action, String... args);

        String statusJson();

        String runtimeConfigJson();

        String memoryJvmJson();

        String memoryListJson();

        String memoryInfoJson(String id);

        String memoryCreate(String id, String mode, String minHeap, String maxHeap);

        boolean memoryStop(String id);

        String memoryLoad(String regionId, String jarPath, String className);

        String uiJson();

        EmbeddedModuleInfo module();

        void log(String message);

        void info(String message);

        void warn(String message);

        void err(String message);

        RemoteNamespace getRemote();

        ServiceNamespace getService();

        RuntimeNamespace getRuntime();

        OpenApiNamespace getOpenapi();

        StatusNamespace getStatus();

        void uiRegisterPage(String pageId, String descriptorJson);

        boolean uiUnregisterPage(String pageId);

        PlayerNamespace getPlayer();

        GameNamespace getGame();

        NetNamespace getNet();

        ContentNamespace getContent();

        WorldNamespace getWorld();

        WsNamespace getWs();

        ComidNamespace getComid();

        PlayerDataNamespace getPlayerData();

        PlayerDataNamespace getData();

        RedisNamespace getRedis();

        SqlNamespace getSql();

        MinioNamespace getMinio();

        DbNamespace getDb();

        ModuleNamespace getModule();

        CommandsNamespace getCommands();

        ModNamespace getMod();

        ResponseNamespace getResponse();
    }

    @FunctionalInterface
    public interface VarArgCallable{
        Object call(Object... args);
    }

    @FunctionalInterface
    public interface WsOpenHandler{
        void handle(String connectionId);
    }

    @FunctionalInterface
    public interface WsMessageHandler{
        void handle(String connectionId, String message, boolean binary);
    }

    @FunctionalInterface
    public interface WsCloseHandler{
        void handle(String connectionId, int statusCode, String reason);
    }

    @FunctionalInterface
    public interface WsErrorHandler{
        void handle(String connectionId, String error);
    }

    public static final class EmbeddedModuleInfo{
        public final String id;
        public final String fullId;
        public final String name;
        public final String author;
        public final String version;
        public final String rootPath;
        public final String scriptsPath;
        public final String dataPath;
        public final String cachePath;

        public EmbeddedModuleInfo(YZFModuleDefinition definition){
            this.id = definition.id();
            this.fullId = definition.fullId();
            this.name = definition.meta.name;
            this.author = definition.author();
            this.version = definition.meta.version;
            this.rootPath = definition.root.absolutePath();
            this.scriptsPath = definition.scriptsDir.absolutePath();
            this.dataPath = definition.dataDir.absolutePath();
            this.cachePath = definition.cacheDir.absolutePath();
        }
    }

    public final class JavaModuleApi extends BaseEmbeddedModuleApi{
        private JavaModuleApi(EmbeddedModuleState state){
            super(state);
        }
    }

    public static final class ResponseNamespace{
        public Jval ok(String code, String message){
            return YZFResponse.ok(code, message);
        }

        public Jval ok(String code, String message, Jval data){
            return YZFResponse.ok(code, message, data);
        }

        public Jval fail(String code, String message){
            return YZFResponse.fail(code, message);
        }

        public Jval fail(String code, String message, Jval data){
            return YZFResponse.fail(code, message, data);
        }
    }

    public static final class OpenApiNamespace{
        public String manifest(){
            return YZFOpenApiRegistry.manifestJson();
        }

        public String list(){
            return YZFOpenApiRegistry.listJson();
        }

        public String info(String capabilityId){
            return YZFOpenApiRegistry.infoJson(capabilityId);
        }

        public String summary(){
            return YZFOpenApiRegistry.summaryJson();
        }

        public String readOnly(){
            return YZFOpenApiRegistry.readOnlyJson();
        }

        public String writeOnly(){
            return YZFOpenApiRegistry.writeOnlyJson();
        }
    }

    public static final class StatusNamespace{
        public String snapshot(){
            return YZFStatusUi.statusJson();
        }

        public String ui(){
            return YZFStatusUi.uhdStatusUiJson();
        }
    }

    public static final class RemoteNamespace{
        private final YZFScriptServices services;

        private RemoteNamespace(YZFScriptServices services){
            this.services = services;
        }

        public String get(String serviceId, String path) throws Exception{
            return services.httpGet(serviceId, path);
        }

        public String postJson(String serviceId, String path, String body) throws Exception{
            return services.httpPostJson(serviceId, path, body);
        }
    }

    public static final class ServiceNamespace{
        private final YZFScriptServices services;

        private ServiceNamespace(YZFScriptServices services){
            this.services = services;
        }

        public boolean has(String serviceId){
            return MindustryYZF.context().services.registry().get(serviceId) != null;
        }

        public String summary(String serviceId){
            YZFServiceClient client = MindustryYZF.context().services.registry().get(serviceId);
            return client == null ? null : client.summary();
        }

        public String list(){
            Jval array = Jval.newArray();
            for(String id : MindustryYZF.context().services.registry().ids()){
                array.add(id);
            }
            return array.toString(Jval.Jformat.plain);
        }

        public String info(String serviceId){
            YZFServiceClient client = MindustryYZF.context().services.registry().get(serviceId);
            if(client == null) return null;
            Jval root = Jval.newObject();
            root.put("id", client.config().id);
            root.put("type", String.valueOf(client.config().type));
            root.put("summary", client.summary());
            root.put("healthy", client.healthy());
            root.put("healthDetails", client.healthDetails());
            root.put("configPath", client.config().sourcePath);
            root.put("enabled", client.config().enabled);
            root.put("clusterMode", String.valueOf(client.config().clusterMode));
            root.put("endpoint", client.config().endpoint);
            root.put("database", client.config().database);
            root.put("databaseFile", client.config().databaseFile);
            root.put("bucket", client.config().bucket);
            root.put("username", client.config().username);
            root.put("region", client.config().region);
            root.put("namespace", client.config().namespace);
            root.put("nodeCount", client.config().nodes.size);
            root.put("optionCount", client.config().options.size);
            return root.toString(Jval.Jformat.plain);
        }

        public String call(String serviceId, String action, String... args) throws Exception{
            return services.serviceCall(serviceId, action, args == null ? new String[0] : args);
        }
    }

    public static final class RuntimeNamespace{
        private final EmbeddedModuleState state;

        private RuntimeNamespace(EmbeddedModuleState state){
            this.state = state;
        }

        public String mode(){
            return MindustryYZF.context().runtime.mode();
        }

        public boolean watcherRunning(){
            return MindustryYZF.context().watcher.running();
        }

        public String configJson(){
            Jval root = Jval.newObject();
            for(java.util.Map.Entry<String, Object> entry : MindustryYZF.context().runtimeConfig.snapshot().entrySet()){
                Object value = entry.getValue();
                if(value instanceof Number number) root.put(entry.getKey(), number);
                else if(value instanceof Boolean bool) root.put(entry.getKey(), bool);
                else root.put(entry.getKey(), value == null ? null : String.valueOf(value));
            }
            return root.toString(Jval.Jformat.plain);
        }

        public void reloadSelf(){
            MindustryYZF.context().runtime.reloadModule(state.definition.fullId());
        }

        public void reloadModule(String moduleId){
            MindustryYZF.context().runtime.reloadModule(moduleId);
        }

        public void reloadAll(){
            MindustryYZF.context().runtime.reloadAll();
        }

        public boolean terminateModule(String moduleId){
            if(MindustryYZF.context().runtime instanceof YZFJsRuntime runtime){
                return runtime.terminateModule(moduleId);
            }
            return false;
        }

        public boolean setMemory(String moduleId, String minHeap, String maxHeap){
            if(MindustryYZF.context().runtime instanceof YZFJsRuntime runtime){
                return runtime.setModuleMemoryLimits(moduleId, minHeap, maxHeap);
            }
            return false;
        }
    }

    private static final class PlayerNamespace{
        private final YZFJsModuleBridge helper;

        private PlayerNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public boolean kick(int playerId, String reason){
            return helper.playerKick(playerId, reason);
        }

        public boolean kick(int playerId, String reason, long durationMs){
            return helper.playerKickDuration(playerId, reason, durationMs);
        }

        public boolean ban(int playerId){
            return helper.playerBan(playerId);
        }

        public boolean banIP(String ip){
            return helper.playerBanIP(ip);
        }

        public boolean banID(String uuidOrComid){
            return helper.playerBanID(uuidOrComid);
        }

        public boolean unbanIP(String ip){
            return helper.playerUnbanIP(ip);
        }

        public boolean unbanID(String uuidOrComid){
            return helper.playerUnbanID(uuidOrComid);
        }

        public boolean admin(int playerId, boolean admin){
            return helper.playerAdmin(playerId, admin);
        }

        public boolean adminByComid(long comid, boolean admin){
            return helper.playerAdminComid(comid, admin);
        }

        public String info(int playerId){
            return helper.playerInfo(playerId);
        }

        public String infoByComid(long comid){
            return helper.playerInfoByComid(comid);
        }

        public String list(){
            return helper.playerList();
        }

        public String find(String nameOrId){
            return helper.playerFind(nameOrId);
        }

        public void send(int playerId, String message){
            helper.playerSend(playerId, message);
        }

        public int count(){
            return helper.playerCount();
        }
    }

    private static final class GameNamespace{
        private final YZFJsModuleBridge helper;

        private GameNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public int wave(){
            return helper.gameWave();
        }

        public void setWave(int wave){
            helper.gameSetWave(wave);
        }

        public float waveTime(){
            return helper.gameWaveTime();
        }

        public void setWaveTime(float ticks){
            helper.gameSetWaveTime(ticks);
        }

        public void skipWave(){
            helper.gameSkipWave();
        }

        public double tick(){
            return helper.gameTick();
        }

        public int tps(){
            return helper.gameTps();
        }

        public String map(){
            return helper.gameMap();
        }

        public boolean isPlaying(){
            return helper.gameIsPlaying();
        }

        public boolean isPaused(){
            return helper.gameIsPaused();
        }

        public boolean isCampaign(){
            return helper.gameIsCampaign();
        }

        public boolean isPvp(){
            return helper.gameIsPvp();
        }

        public boolean isAttack(){
            return helper.gameIsAttack();
        }

        public int enemies(){
            return helper.gameEnemies();
        }

        public String rules(){
            return helper.gameRules();
        }

        public boolean setRule(String key, String value){
            return helper.gameSetRule(key, value);
        }
    }

    public static final class NetNamespace{
        private final YZFJsModuleBridge helper;

        private NetNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public void send(int playerId, String message){
            helper.netSend(playerId, message);
        }

        public void broadcast(String message){
            helper.netBroadcast(message);
        }

        public void broadcastFrom(String message, int senderId){
            helper.netBroadcastFrom(message, senderId);
        }
    }

    private static final class ContentNamespace{
        private final YZFJsModuleBridge helper;

        private ContentNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public String block(String name){
            return helper.contentBlock(name);
        }

        public String item(String name){
            return helper.contentItem(name);
        }

        public String liquid(String name){
            return helper.contentLiquid(name);
        }

        public String unit(String name){
            return helper.contentUnit(name);
        }

        public String status(String name){
            return helper.contentStatus(name);
        }

        public String weather(String name){
            return helper.contentWeather(name);
        }

        public String planet(String name){
            return helper.contentPlanet(name);
        }

        public String blocks(){
            return helper.contentBlocks();
        }

        public String items(){
            return helper.contentItems();
        }

        public String liquids(){
            return helper.contentLiquids();
        }

        public String units(){
            return helper.contentUnits();
        }

        public void registerMeta(String namespace, String name, String json){
            helper.contentRegisterMeta(namespace, name, json);
        }

        public String getMeta(String namespace, String name){
            return helper.contentGetMeta(namespace, name);
        }

        public String listMeta(String namespace){
            return helper.contentListMeta(namespace);
        }

        public String listNamespaces(){
            return helper.contentListNamespaces();
        }

        public boolean removeMeta(String namespace, String name){
            return helper.contentRemoveMeta(namespace, name);
        }

        public String setProperty(String contentName, String property, String value){
            return helper.contentSetProperty(contentName, property, value);
        }

        public String getProperty(String contentName, String property){
            return helper.contentGetProperty(contentName, property);
        }
    }

    private static final class WorldNamespace{
        private final YZFJsModuleBridge helper;

        private WorldNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public String spawn(String type, String id, int x, int y, int size, int teamId, String buff){
            return helper.worldSpawn(type, id, x, y, size, teamId, buff);
        }

        public String fill(String itemId, int amount, int teamId){
            return helper.worldFill(itemId, amount, teamId);
        }

        public String batchSpawn(String operationsJson){
            return helper.worldBatchSpawn(operationsJson);
        }
    }

    private final class WsNamespace{
        private final EmbeddedModuleState state;

        private WsNamespace(EmbeddedModuleState state){
            this.state = state;
        }

        public String connect(String url, WsOpenHandler onOpen, WsMessageHandler onMessage, WsCloseHandler onClose, WsErrorHandler onError){
            if(wsConnections.size() >= maxEmbeddedWebSocketConnections) return null;
            String id = "ews-" + nextWsId.getAndIncrement();
            try{
                URI uri = URI.create(url);
                YZFExternalAccessConfig access = MindustryYZF.externalAccess();
                if(access != null && !access.allowsOutbound(uri)) throw new SecurityException("external WebSocket target is not permitted: " + uri);
                HttpClient client = HttpClient.newBuilder().executor(wsExecutor()).connectTimeout(Duration.ofSeconds(10)).build();
                String moduleId = state.definition.fullId();
                EmbeddedWsListener listener = new EmbeddedWsListener(id, onOpen, onMessage, onClose, onError);
                EmbeddedWsConnection connection = new EmbeddedWsConnection(id, moduleId, url, listener);
                wsConnections.put(id, connection);
                moduleWsIds.computeIfAbsent(moduleId, key -> new CopyOnWriteIds()).ids.add(id);
                WebSocket.Builder builder = client.newWebSocketBuilder();
                if(access != null && access.attachOutboundToken()) builder.header("Authorization", access.authorization());
                builder.buildAsync(uri, listener).whenComplete((socket, error) -> {
                    if(error != null){
                        removeWsTracking(id);
                        if(onError != null && !listener.suppressCallbacks){
                            String message = error.getMessage() == null ? String.valueOf(error) : error.getMessage();
                            postEmbeddedCallback("ws-error", id, () -> onError.handle(id, message));
                        }
                        return;
                    }
                    listener.webSocket = socket;
                    connection.webSocket = socket;
                    if(connection.closed || listener.closed){
                        try{ socket.abort(); }catch(Throwable ignored){}
                    }
                });
                return id;
            }catch(Exception e){
                if(onError != null){
                    String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    postEmbeddedCallback("ws-error", id, () -> onError.handle(id, message));
                }
                return null;
            }
        }

        public boolean send(String connectionId, String message){
            EmbeddedWsConnection connection = wsConnections.get(connectionId);
            if(connection == null || connection.closed || connection.webSocket == null) return false;
            try{
                connection.webSocket.sendText(message, true);
                return true;
            }catch(Exception ignored){
                return false;
            }
        }

        public boolean sendBinary(String connectionId, String base64Data){
            EmbeddedWsConnection connection = wsConnections.get(connectionId);
            if(connection == null || connection.closed || connection.webSocket == null) return false;
            try{
                byte[] bytes = Base64.getDecoder().decode(base64Data);
                if(bytes.length > maxWebSocketMessageChars) return false;
                connection.webSocket.sendBinary(ByteBuffer.wrap(bytes), true);
                return true;
            }catch(Exception ignored){
                return false;
            }
        }

        public void close(String connectionId){
            wsClose(connectionId);
        }

        public boolean isOpen(String connectionId){
            EmbeddedWsConnection connection = wsConnections.get(connectionId);
            return connection != null && !connection.closed && !connection.listener.closed;
        }

        public String list(){
            Jval array = Jval.newArray();
            for(EmbeddedWsConnection connection : wsConnections.values()){
                Jval item = Jval.newObject();
                item.put("id", connection.id);
                item.put("url", connection.url);
                item.put("open", !connection.closed && !connection.listener.closed);
                array.add(item);
            }
            return array.toString(Jval.Jformat.plain);
        }
    }

    private static final class ComidNamespace{
        private final YZFJsModuleBridge helper;

        private ComidNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public long get(String uuid){
            return helper.comidGet(uuid);
        }

        public long getOrCreate(String uuid){
            return helper.comidGetOrCreate(uuid);
        }

        public String getUuid(long comid){
            return helper.comidGetUuid(comid);
        }

        public boolean exists(long comid){
            return helper.comidExists(comid);
        }

        public int digits(){
            return helper.comidDigits();
        }

        public long remaining(){
            return helper.comidRemaining();
        }

        public int total(){
            return helper.comidTotal();
        }
    }

    private static final class PlayerDataNamespace{
        private final YZFJsModuleBridge helper;

        private PlayerDataNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public String get(long comid, String key){
            return helper.playerDataGet(comid, key);
        }

        public String get(long comid, String key, String defaultValue){
            return helper.playerDataGetDefault(comid, key, defaultValue);
        }

        public void set(long comid, String key, String value){
            helper.playerDataSet(comid, key, value);
        }

        public int getInt(long comid, String key, int defaultValue){
            return helper.playerDataGetInt(comid, key, defaultValue);
        }

        public void setInt(long comid, String key, int value){
            helper.playerDataSetInt(comid, key, value);
        }

        public boolean getBool(long comid, String key, boolean defaultValue){
            return helper.playerDataGetBool(comid, key, defaultValue);
        }

        public void setBool(long comid, String key, boolean value){
            helper.playerDataSetBool(comid, key, value);
        }

        public double getDouble(long comid, String key, double defaultValue){
            return helper.playerDataGetDouble(comid, key, defaultValue);
        }

        public void setDouble(long comid, String key, double value){
            helper.playerDataSetDouble(comid, key, value);
        }

        public String getAll(long comid){
            return helper.playerDataGetAll(comid);
        }

        public void remove(long comid, String key){
            helper.playerDataRemove(comid, key);
        }

        public void clear(long comid){
            helper.playerDataClear(comid);
        }
    }

    private static final class RedisNamespace{
        private final YZFScriptServices services;

        private RedisNamespace(YZFScriptServices services){
            this.services = services;
        }

        public String get(String serviceId, String key){
            return services.redisGet(serviceId, key);
        }

        public void set(String serviceId, String key, String value){
            services.redisSet(serviceId, key, value);
        }

        public void del(String serviceId, String key){
            services.redisDelete(serviceId, key);
        }

        public long incr(String serviceId, String key){
            return services.redisIncrement(serviceId, key);
        }

        public String hget(String serviceId, String key, String field){
            return services.redisHashGet(serviceId, key, field);
        }

        public void hset(String serviceId, String key, String field, String value){
            services.redisHashSet(serviceId, key, field, value);
        }
    }

    private static final class SqlNamespace{
        private final YZFScriptServices services;

        private SqlNamespace(YZFScriptServices services){
            this.services = services;
        }

        public String queryFirstCell(String serviceId, String sql) throws Exception{
            return services.sqlQueryFirstCell(serviceId, sql);
        }

        public int execute(String serviceId, String sql) throws Exception{
            return services.sqlExecute(serviceId, sql);
        }

        public String queryJson(String serviceId, String sql) throws Exception{
            return services.sqlQueryJson(serviceId, sql);
        }
    }

    private static final class MinioNamespace{
        private final YZFScriptServices services;

        private MinioNamespace(YZFScriptServices services){
            this.services = services;
        }

        public void putText(String serviceId, String objectName, String text) throws Exception{
            services.minioPutText(serviceId, objectName, text);
        }
    }

    private static final class DbNamespace{
        private final YZFJsModuleBridge helper;

        private DbNamespace(YZFJsModuleBridge helper){
            this.helper = helper;
        }

        public String list(){
            return helper.dbList();
        }

        public String info(String id){
            return helper.dbInfo(id);
        }

        public boolean has(String id){
            return helper.dbHas(id);
        }

        public boolean addLocal(String id, String name){
            return helper.dbAddLocal(id, name);
        }

        public boolean addRemote(String id, String name, String endpoint, String serviceId, boolean readOnly){
            return helper.dbAddRemote(id, name, endpoint, serviceId, readOnly);
        }

        public boolean remove(String id){
            return helper.dbRemove(id);
        }

        public String categories(String id) throws Exception{
            return helper.dbCategories(id);
        }

        public String keys(String id, String category) throws Exception{
            return helper.dbKeys(id, category);
        }

        public String get(String id, String category, String key) throws Exception{
            return helper.dbGet(id, category, key);
        }

        public void set(String id, String category, String key, String valueJson) throws Exception{
            helper.dbSet(id, category, key, valueJson);
        }

        public boolean removeEntry(String id, String category, String key) throws Exception{
            return helper.dbRemoveEntry(id, category, key);
        }

        public String dump(String id) throws Exception{
            return helper.dbDump(id);
        }

        public void importJson(String id, String json) throws Exception{
            helper.dbImport(id, json);
        }

        public String defaultId(){
            return helper.dbDefaultId();
        }

        public int count(){
            return helper.dbCount();
        }
    }

    public final class ModuleNamespace{
        private final EmbeddedModuleState state;

        private ModuleNamespace(EmbeddedModuleState state){
            this.state = state;
        }

        public void export(String fnName, VarArgCallable callback){
            if(YZFText.blank(fnName) || callback == null){
                throw new IllegalArgumentException("module.export requires a name and callback.");
            }
            embeddedExports.computeIfAbsent(state.definition.fullId(), key -> new ConcurrentHashMap<>())
                .put(fnName, new EmbeddedExport(state.definition.fullId(), fnName, callback));
        }

        public Object call(String targetModuleId, String fnName, Object... args){
            ConcurrentHashMap<String, EmbeddedExport> exports = embeddedExports.get(targetModuleId);
            if(exports != null){
                EmbeddedExport export = exports.get(fnName);
                if(export != null){
                    return export.callback.call(args == null ? new Object[0] : args);
                }
            }
            return YZFJsModuleBridge.callExportedFunction(targetModuleId, fnName, args == null ? new Object[0] : args);
        }

        public String exportedFunctions(String targetModuleId){
            Jval array = Jval.newArray();
            ConcurrentHashMap<String, EmbeddedExport> exports = embeddedExports.get(targetModuleId);
            if(exports != null){
                for(String name : exports.keySet()){
                    array.add(name);
                }
            }
            try{
                Jval js = Jval.read(YZFJsModuleBridge.listExportedFunctionsStatic(targetModuleId));
                if(js != null && js.isArray()){
                    for(Jval value : js.asArray()){
                        if(value != null && value.isString()) array.add(value.asString());
                    }
                }
            }catch(Exception ignored){
            }
            return array.toString(Jval.Jformat.plain);
        }

        public String list(){
            Jval array = Jval.newArray();
            for(YZFModuleDefinition definition : MindustryYZF.context().registry.modules()){
                array.add(definition.fullId());
            }
            return array.toString(Jval.Jformat.plain);
        }

        public String info(String moduleId){
            return new YZFJsModuleBridge(null, state.definition, null).moduleInfo(moduleId);
        }
    }

    Object callExportedFunction(String targetModuleId, String fnName, Object[] args){
        ConcurrentHashMap<String, EmbeddedExport> exports = embeddedExports.get(targetModuleId);
        if(exports == null) throw new IllegalArgumentException("Module has no embedded exports: " + targetModuleId);
        EmbeddedExport export = exports.get(fnName);
        if(export == null) throw new IllegalArgumentException("Module has no embedded export: " + targetModuleId + "/" + fnName);
        return export.callback.call(args == null ? new Object[0] : args);
    }

    private final class CommandsNamespace{
        private final EmbeddedModuleState state;

        private CommandsNamespace(EmbeddedModuleState state){
            this.state = state;
        }

        public void register(String name, String description, VarArgCallable callback){
            if(YZFText.blank(name) || callback == null){
                throw new IllegalArgumentException("commands.register requires a name and callback.");
            }
            EmbeddedCallableCommand existing = embeddedCommands.get(name);
            if(existing != null && !existing.moduleId.equals(state.definition.fullId())){
                throw new IllegalStateException("command '" + name + "' is already registered by module " + existing.moduleId);
            }
            embeddedCommands.put(name, new EmbeddedCallableCommand(state.definition.fullId(), name, description == null ? "" : description, callback));
            moduleCommands.computeIfAbsent(state.definition.fullId(), key -> new CopyOnWriteIds()).ids.add(name);
        }

        public Object call(String name, Object... args){
            EmbeddedCallableCommand command = embeddedCommands.get(name);
            if(command != null){
                return command.callback.call(args == null ? new Object[0] : args);
            }
            return MindustryYZF.context().commandRegistry.call(name, args == null ? new Object[0] : args);
        }

        public boolean has(String name){
            return embeddedCommands.containsKey(name) || MindustryYZF.context().commandRegistry.has(name);
        }

        public void unregister(String name){
            EmbeddedCallableCommand command = embeddedCommands.get(name);
            if(command != null && command.moduleId.equals(state.definition.fullId())){
                embeddedCommands.remove(name);
            }
            moduleCommands.computeIfAbsent(state.definition.fullId(), key -> new CopyOnWriteIds()).ids.remove(name);
            MindustryYZF.context().commandRegistry.unregister(state.definition.fullId(), name);
        }

        public String list(){
            Jval array = Jval.newArray();
            try{
                Jval js = Jval.read(MindustryYZF.context().commandRegistry.listAsJson());
                if(js != null && js.isArray()){
                    for(Jval item : js.asArray()) array.add(item);
                }
            }catch(Exception ignored){
            }
            for(EmbeddedCallableCommand command : embeddedCommands.values()){
                Jval item = Jval.newObject();
                item.put("name", command.name);
                item.put("description", command.description);
                item.put("module", command.moduleId);
                array.add(item);
            }
            return array.toString(Jval.Jformat.plain);
        }

        public String listModule(String moduleId){
            Jval array = Jval.newArray();
            try{
                Jval js = Jval.read(MindustryYZF.context().commandRegistry.listModuleAsJson(moduleId));
                if(js != null && js.isArray()){
                    for(Jval item : js.asArray()) array.add(item);
                }
            }catch(Exception ignored){
            }
            for(EmbeddedCallableCommand command : embeddedCommands.values()){
                if(!command.moduleId.equals(moduleId)) continue;
                Jval item = Jval.newObject();
                item.put("name", command.name);
                item.put("description", command.description);
                array.add(item);
            }
            return array.toString(Jval.Jformat.plain);
        }

        public boolean run(String commandName, String[] args){
            return invokeServerCommand(commandName, args == null ? new String[0] : args);
        }
    }

    private final class ModNamespace{
        private final EmbeddedModuleState state;
        private final CommandsNamespace commands;
        private final BaseEmbeddedModuleApi owner;

        private ModNamespace(EmbeddedModuleState state, CommandsNamespace commands, BaseEmbeddedModuleApi owner){
            this.state = state;
            this.commands = commands;
            this.owner = owner;
        }

        public void registerServerCommand(String name, String usage, String description, Consumer<String[]> callback){
            owner.registerServerCommand(name, usage, description, callback);
        }

        public void registerPlayerCommand(String name, String usage, String description, boolean adminOnly, String permission, BiConsumer<Player, String[]> callback){
            if(adminOnly){
                owner.adminCommand(name, usage, description, permission, callback);
            }else{
                owner.registerPlayerCommand(name, usage, description, permission, false, callback);
            }
        }

        public void registerCallableCommand(String name, String description, VarArgCallable callback){
            commands.register(name, description, callback);
        }

        public boolean unregisterCommand(String name){
            boolean removed = false;
            if(state.serverCommands.contains(name)){
                MindustryYZF.context().serverControl.handler.removeCommand(name);
                state.serverCommands.remove(name);
                removed = true;
            }
            for(int i = state.playerCommands.size - 1; i >= 0; i--){
                if(state.playerCommands.get(i).name.equals(name)){
                    Vars.netServer.clientCommands.removeCommand(name);
                    state.playerCommands.remove(i);
                    removed = true;
                }
            }
            if(embeddedCommands.containsKey(name)){
                commands.unregister(name);
                removed = true;
            }
            return removed;
        }

        public String listCommands(){
            Jval root = Jval.newObject();
            Jval server = Jval.newArray();
            for(String name : state.serverCommands) server.add(name);
            Jval player = Jval.newArray();
            for(YZFPlayerCommandBinding binding : state.playerCommands) player.add(binding.name);
            Jval callable = Jval.newArray();
            for(EmbeddedCallableCommand command : embeddedCommands.values()){
                if(command.moduleId.equals(state.definition.fullId())) callable.add(command.name);
            }
            root.put("server", server);
            root.put("player", player);
            root.put("callable", callable);
            return root.toString(Jval.Jformat.plain);
        }

        public boolean hasCommand(String name){
            if(state.serverCommands.contains(name)) return true;
            for(YZFPlayerCommandBinding binding : state.playerCommands){
                if(binding.name.equals(name)) return true;
            }
            EmbeddedCallableCommand command = embeddedCommands.get(name);
            return command != null && command.moduleId.equals(state.definition.fullId());
        }
    }

    private abstract class BaseEmbeddedModuleApi implements EmbeddedModuleApi{
        protected final EmbeddedModuleState state;
        protected final YZFModuleConfigStore configStore;
        protected final YZFScriptServices services;
        protected final YZFJsModuleBridge helper;
        public final RemoteNamespace remote;
        public final ServiceNamespace service;
        public final RuntimeNamespace runtime;
        public final OpenApiNamespace openapi;
        public final StatusNamespace status;
        public final PlayerNamespace player;
        public final GameNamespace game;
        public final NetNamespace net;
        public final ContentNamespace content;
        public final WorldNamespace world;
        public final WsNamespace ws;
        public final ComidNamespace comid;
        public final PlayerDataNamespace playerData;
        public final PlayerDataNamespace data;
        public final RedisNamespace redis;
        public final SqlNamespace sql;
        public final MinioNamespace minio;
        public final DbNamespace db;
        public final ModuleNamespace module;
        public final CommandsNamespace commands;
        public final ModNamespace mod;
        public final ResponseNamespace response = new ResponseNamespace();

        private BaseEmbeddedModuleApi(EmbeddedModuleState state){
            this.state = state;
            this.configStore = new YZFModuleConfigStore(state.definition);
            this.services = new YZFScriptServices(MindustryYZF.context());
            this.helper = new YZFJsModuleBridge(null, state.definition, null);
            this.remote = new RemoteNamespace(services);
            this.service = new ServiceNamespace(services);
            this.runtime = new RuntimeNamespace(state);
            this.openapi = new OpenApiNamespace();
            this.status = new StatusNamespace();
            this.player = new PlayerNamespace(helper);
            this.game = new GameNamespace(helper);
            this.net = new NetNamespace(helper);
            this.content = new ContentNamespace(helper);
            this.world = new WorldNamespace(helper);
            this.ws = new WsNamespace(state);
            this.comid = new ComidNamespace(helper);
            this.playerData = new PlayerDataNamespace(helper);
            this.data = this.playerData;
            this.redis = new RedisNamespace(services);
            this.sql = new SqlNamespace(services);
            this.minio = new MinioNamespace(services);
            this.db = new DbNamespace(helper);
            this.module = new ModuleNamespace(state);
            this.commands = new CommandsNamespace(state);
            this.mod = new ModNamespace(state, commands, this);
        }

        @Override
        public void onEnable(Runnable callback){
            if(callback != null) state.enableCallbacks.add(callback);
        }

        @Override
        public void onDisable(Runnable callback){
            if(callback != null) state.disableCallbacks.add(callback);
        }

        @Override
        public void command(String name, String usage, String description, Consumer<String[]> callback){
            registerServerCommand(name, usage, description, callback);
        }

        @Override
        public void playerCommand(String name, String usage, String description, BiConsumer<Player, String[]> callback){
            registerPlayerCommand(name, usage, description, state.definition.meta.permission, false, callback);
        }

        @Override
        public void adminCommand(String name, String usage, String description, String permission, BiConsumer<Player, String[]> callback){
            registerPlayerCommand(name, usage, description, YZFText.blank(permission) ? state.definition.meta.permission : permission, true, callback);
        }

        @Override
        public void after(float delaySeconds, Runnable callback){
            Timer.Task task = Timer.schedule(() -> YZFCallbackGuard.run(state.definition.fullId(), "timer-once", callback), delaySeconds);
            state.taskBindings.add(new YZFTaskBinding("after-" + state.taskBindings.size, "once", task));
        }

        @Override
        public void every(float delaySeconds, float intervalSeconds, Runnable callback){
            Timer.Task task = Timer.schedule(() -> YZFCallbackGuard.run(state.definition.fullId(), "timer-repeat", callback), delaySeconds, intervalSeconds);
            state.taskBindings.add(new YZFTaskBinding("every-" + state.taskBindings.size, "repeat", task));
        }

        @Override
        public void on(String eventName, Consumer<String> handler){
            String normalized = blank(eventName);
            if(YZFText.blank(normalized)) return;
            Class<?> eventType = YZFEventRegistry.find(normalized);
            if(eventType == null){
                throw new IllegalArgumentException("Unknown event type: " + normalized);
            }
            Cons<Object> callback = event -> handler.accept(serializeEvent(event));
            Events.on((Class)eventType, (Cons)callback);
            state.eventBindings.add(new YZFEventBinding(normalized, eventType, callback));
        }

        @Override
        public String configGet(String key, String defaultValue){
            return configStore.getString(blank(key), blank(defaultValue));
        }

        @Override
        public boolean configGetBool(String key, boolean defaultValue){
            return configStore.getBool(blank(key), defaultValue);
        }

        @Override
        public int configGetInt(String key, int defaultValue){
            return configStore.getInt(blank(key), defaultValue);
        }

        @Override
        public void configSet(String key, String value){
            configStore.putString(blank(key), blank(value));
        }

        @Override
        public void configSetBool(String key, boolean value){
            configStore.putBool(blank(key), value);
        }

        @Override
        public void configSetInt(String key, int value){
            configStore.putInt(blank(key), value);
        }

        @Override
        public String configPath(){
            return configStore.path();
        }

        @Override
        public String serviceCall(String serviceId, String action, String... args){
            try{
                return services.serviceCall(blank(serviceId), blank(action), args == null ? new String[0] : args);
            }catch(Exception e){
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        @Override
        public String statusJson(){
            return YZFStatusUi.statusJson();
        }

        @Override
        public String runtimeConfigJson(){
            return mapJson(MindustryYZF.context().runtimeConfig.snapshot());
        }

        @Override
        public String memoryJvmJson(){
            return mapJson(MindustryYZF.context().memoryRegions.jvmSnapshot());
        }

        @Override
        public String memoryListJson(){
            Jval array = Jval.newArray();
            for(java.util.Map<String, Object> item : MindustryYZF.context().memoryRegions.list()) array.add(mapJson(item));
            return array.toString(Jval.Jformat.plain);
        }

        @Override
        public String memoryInfoJson(String id){
            YZFMemoryRegion region = MindustryYZF.context().memoryRegions.get(blank(id));
            return region == null ? null : mapJson(region.snapshot());
        }

        @Override
        public String memoryCreate(String id, String mode, String minHeap, String maxHeap){
            if(!MindustryYZF.context().runtimeConfig.allowPluginCreateRegion) throw new IllegalStateException("plugin region creation is disabled");
            YZFMemoryRegion region = MindustryYZF.context().memoryRegions.create(blank(id), blank(mode), YZFMemoryRegionManager.parseBytes(minHeap), YZFMemoryRegionManager.parseBytes(maxHeap));
            return mapJson(region.snapshot());
        }

        @Override
        public boolean memoryStop(String id){
            return MindustryYZF.context().memoryRegions.stop(blank(id));
        }

        @Override
        public String memoryLoad(String regionId, String jarPath, String className){
            try{
                return MindustryYZF.context().memoryRegions.loadClassLoaderJar(blank(regionId), blank(jarPath), blank(className));
            }catch(Exception error){
                throw new IllegalStateException(error.getMessage(), error);
            }
        }

        private String mapJson(java.util.Map<String, ?> map){
            Jval root = Jval.newObject();
            for(java.util.Map.Entry<String, ?> entry : map.entrySet()){
                Object value = entry.getValue();
                if(value instanceof Number number) root.put(entry.getKey(), number);
                else if(value instanceof Boolean bool) root.put(entry.getKey(), bool);
                else root.put(entry.getKey(), value == null ? null : String.valueOf(value));
            }
            return root.toString(Jval.Jformat.plain);
        }

        @Override
        public String uiJson(){
            return YZFStatusUi.uhdStatusUiJson();
        }

        @Override
        public EmbeddedModuleInfo module(){
            return new EmbeddedModuleInfo(state.definition);
        }

        @Override
        public void log(String message){
            helper.log(blank(message));
        }

        @Override
        public void info(String message){
            helper.info(blank(message));
        }

        @Override
        public void warn(String message){
            helper.warn(blank(message));
        }

        @Override
        public void err(String message){
            helper.err(blank(message));
        }

        @Override
        public RemoteNamespace getRemote(){
            return remote;
        }

        @Override
        public ServiceNamespace getService(){
            return service;
        }

        @Override
        public RuntimeNamespace getRuntime(){
            return runtime;
        }

        @Override
        public OpenApiNamespace getOpenapi(){
            return openapi;
        }

        @Override
        public StatusNamespace getStatus(){
            return status;
        }

        @Override
        public void uiRegisterPage(String pageId, String descriptorJson){
            MindustryYZF.context().webUi.register(state.definition.fullId(), pageId, descriptorJson);
        }

        @Override
        public boolean uiUnregisterPage(String pageId){
            return MindustryYZF.context().webUi.unregister(state.definition.fullId(), pageId);
        }

        @Override
        public PlayerNamespace getPlayer(){
            return player;
        }

        @Override
        public GameNamespace getGame(){
            return game;
        }

        @Override
        public NetNamespace getNet(){
            return net;
        }

        @Override
        public ContentNamespace getContent(){
            return content;
        }

        @Override
        public WorldNamespace getWorld(){
            return world;
        }

        @Override
        public WsNamespace getWs(){
            return ws;
        }

        @Override
        public ComidNamespace getComid(){
            return comid;
        }

        @Override
        public PlayerDataNamespace getPlayerData(){
            return playerData;
        }

        @Override
        public PlayerDataNamespace getData(){
            return data;
        }

        @Override
        public RedisNamespace getRedis(){
            return redis;
        }

        @Override
        public SqlNamespace getSql(){
            return sql;
        }

        @Override
        public MinioNamespace getMinio(){
            return minio;
        }

        @Override
        public DbNamespace getDb(){
            return db;
        }

        @Override
        public ModuleNamespace getModule(){
            return module;
        }

        @Override
        public CommandsNamespace getCommands(){
            return commands;
        }

        @Override
        public ModNamespace getMod(){
            return mod;
        }

        @Override
        public ResponseNamespace getResponse(){
            return response;
        }

        protected void registerServerCommand(String name, String usage, String description, Consumer<String[]> callback){
            String commandName = normalizeCommand(name);
            if(hasForeignServerCommand(state, commandName)){
                throw new IllegalStateException("Server command already exists: " + commandName);
            }
            CommandHandler handler = MindustryYZF.context().serverControl.handler;
            handler.removeCommand(commandName);
            handler.register(commandName, blank(usage), blank(description), args -> {
                MindustryYZF.context().metrics.serverCommandCalls.incrementAndGet();
                callback.accept(args);
            });
            if(!state.serverCommands.contains(commandName)){
                state.serverCommands.add(commandName);
            }
        }

        protected void registerPlayerCommand(String name, String usage, String description, String permission, boolean adminOnly, BiConsumer<Player, String[]> callback){
            String commandName = normalizeCommand(name);
            if(hasForeignPlayerCommand(state, commandName)){
                throw new IllegalStateException("Player command already exists: " + commandName);
            }
            CommandHandler handler = Vars.netServer.clientCommands;
            handler.removeCommand(commandName);
            handler.<Player>register(commandName, blank(usage), blank(description), (args, player) -> {
                if(adminOnly && (player == null || !player.admin)){
                    if(player != null) player.sendMessage("[scarlet]This command is admin-only.");
                    return;
                }
                if(!YZFText.blank(permission) && !MindustryYZF.context().permissions.has(player, permission)){
                    MindustryYZF.context().metrics.permissionDenied.incrementAndGet();
                    MindustryYZF.context().audit.record("permission-denied", state.definition.fullId(), commandName + " -> " + permission);
                    if(player != null) player.sendMessage("[scarlet]You do not have permission to use this command.");
                    return;
                }
                MindustryYZF.context().metrics.playerCommandCalls.incrementAndGet();
                callback.accept(player, args);
            });

            boolean present = false;
            for(YZFPlayerCommandBinding binding : state.playerCommands){
                if(binding.name.equals(commandName)){
                    present = true;
                    break;
                }
            }
            if(!present){
                state.playerCommands.add(new YZFPlayerCommandBinding(commandName, adminOnly, blank(permission)));
            }
        }

        protected String blank(String value){
            return value == null ? "" : value;
        }
    }

    private static boolean invokeServerCommand(String commandName, String[] args){
        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        for(CommandHandler.Command cmd : handler.getCommandList()){
            if(cmd.text.equalsIgnoreCase(commandName)){
                StringBuilder builder = new StringBuilder(commandName);
                for(String arg : args){
                    builder.append(' ').append(arg);
                }
                handler.handleMessage(builder.toString());
                return true;
            }
        }
        return false;
    }

    private static boolean hasForeignServerCommand(EmbeddedModuleState state, String name){
        for(CommandHandler.Command command : MindustryYZF.context().serverControl.handler.getCommandList()){
            if(command.text.equalsIgnoreCase(name)){
                return !state.serverCommands.contains(name);
            }
        }
        return false;
    }

    private static boolean hasForeignPlayerCommand(EmbeddedModuleState state, String name){
        for(CommandHandler.Command command : Vars.netServer.clientCommands.getCommandList()){
            if(command.text.equalsIgnoreCase(name)){
                for(YZFPlayerCommandBinding binding : state.playerCommands){
                    if(binding.name.equalsIgnoreCase(name)) return false;
                }
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String input){
        if(YZFText.blank(input)) throw new IllegalArgumentException("Command name cannot be empty.");
        String value = input.trim().toLowerCase();
        if(!YZFSecurity.validCommandName(value)){
            throw new IllegalArgumentException("Illegal command name: " + value);
        }
        return value;
    }

    private static String serializeEvent(Object event){
        Jval root = Jval.newObject();
        root.put("_type", event.getClass().getSimpleName());
        for(var field : event.getClass().getFields()){
            try{
                Object value = field.get(event);
                root.put(field.getName(), value == null ? null : String.valueOf(value));
            }catch(Exception ignored){
            }
        }
        try{
            var playerField = event.getClass().getField("player");
            Object playerObj = playerField.get(event);
            if(playerObj instanceof Player player){
                root.put("playerName", player.name);
                root.put("playerUuid", player.uuid() == null ? "" : player.uuid());
                long comid = player.uuid() == null ? -1 : MindustryYZF.context().comidRegistry.getOrCreate(player.uuid());
                if(comid >= 0){
                    root.put("playerComid", comid);
                }
            }
        }catch(Exception ignored){
        }
        return root.toString(Jval.Jformat.plain);
    }

    private static synchronized ExecutorService wsExecutor(){
        if(wsExecutor == null || wsExecutor.isShutdown()){
            wsExecutor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "MindustryYZF-EmbeddedWebSocket");
                thread.setDaemon(true);
                return thread;
            });
        }
        return wsExecutor;
    }

    private static void wsClose(String connectionId){
        EmbeddedWsConnection connection = removeWsTracking(connectionId);
        if(connection == null) return;
        connection.listener.suppressCallbacks = true;
        connection.listener.closed = true;
        try{
            if(connection.webSocket != null) connection.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
        }catch(Exception ignored){
        }
    }

    private static EmbeddedWsConnection removeWsTracking(String connectionId){
        EmbeddedWsConnection connection = wsConnections.remove(connectionId);
        if(connection == null) return null;
        connection.closed = true;
        CopyOnWriteIds ids = moduleWsIds.get(connection.moduleId);
        if(ids != null){
            ids.ids.remove(connectionId);
            if(ids.ids.isEmpty()){
                moduleWsIds.remove(connection.moduleId, ids);
            }
        }
        return connection;
    }

    private static synchronized void shutdownWebSockets(){
        for(String connectionId : new ArrayList<>(wsConnections.keySet())){
            wsClose(connectionId);
        }
        moduleWsIds.clear();
        if(wsExecutor != null){
            wsExecutor.shutdownNow();
            wsExecutor = null;
        }
    }

    private static final class EmbeddedExport{
        final String moduleId;
        final String functionName;
        final VarArgCallable callback;

        private EmbeddedExport(String moduleId, String functionName, VarArgCallable callback){
            this.moduleId = moduleId;
            this.functionName = functionName;
            this.callback = callback;
        }
    }

    private static final class EmbeddedCallableCommand{
        final String moduleId;
        final String name;
        final String description;
        final VarArgCallable callback;

        private EmbeddedCallableCommand(String moduleId, String name, String description, VarArgCallable callback){
            this.moduleId = moduleId;
            this.name = name;
            this.description = description;
            this.callback = callback;
        }
    }

    private static final class CopyOnWriteIds{
        final java.util.concurrent.CopyOnWriteArrayList<String> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    private static void postEmbeddedCallback(String kind, String id, Runnable callback){
        YZFMainThread.post(() -> {
            if(MindustryYZF.isShuttingDown()) return;
            YZFCallbackGuard.run("embedded-websocket:" + id, kind, callback);
        });
    }

    private static final class EmbeddedWsConnection{
        final String id;
        final String moduleId;
        final String url;
        volatile WebSocket webSocket;
        final EmbeddedWsListener listener;
        volatile boolean closed;
        volatile boolean suppressCallbacks;

        private EmbeddedWsConnection(String id, String moduleId, String url, EmbeddedWsListener listener){
            this.id = id;
            this.moduleId = moduleId;
            this.url = url;
            this.listener = listener;
        }
    }

    private static final class EmbeddedWsListener implements WebSocket.Listener{
        final String id;
        final WsOpenHandler onOpen;
        final WsMessageHandler onMessage;
        final WsCloseHandler onClose;
        final WsErrorHandler onError;
        final StringBuilder messageBuffer = new StringBuilder();
        volatile WebSocket webSocket;
        volatile boolean closed;
        volatile boolean suppressCallbacks;

        private EmbeddedWsListener(String id, WsOpenHandler onOpen, WsMessageHandler onMessage, WsCloseHandler onClose, WsErrorHandler onError){
            this.id = id;
            this.onOpen = onOpen;
            this.onMessage = onMessage;
            this.onClose = onClose;
            this.onError = onError;
        }

        @Override
        public void onOpen(WebSocket webSocket){
            this.webSocket = webSocket;
            webSocket.request(1);
            if(onOpen != null && !suppressCallbacks) postEmbeddedCallback("ws-open", id, () -> onOpen.handle(id));
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last){
            if(messageBuffer.length() + data.length() > maxWebSocketMessageChars){
                closed = true;
                removeWsTracking(id);
                try{ webSocket.abort(); }catch(Throwable ignored){}
                if(onError != null && !suppressCallbacks){
                    postEmbeddedCallback("ws-error", id, () -> onError.handle(id, "WebSocket message exceeds 1 MiB limit"));
                }
                return null;
            }
            messageBuffer.append(data);
            if(last){
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                if(onMessage != null && !suppressCallbacks) postEmbeddedCallback("ws-message", id, () -> onMessage.handle(id, message, false));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last){
            if(data.remaining() > maxWebSocketMessageChars){
                closed = true;
                removeWsTracking(id);
                try{ webSocket.abort(); }catch(Throwable ignored){}
                if(onError != null && !suppressCallbacks){
                    postEmbeddedCallback("ws-error", id, () -> onError.handle(id, "WebSocket binary message exceeds 1 MiB limit"));
                }
                return null;
            }
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if(onMessage != null && !suppressCallbacks){
                String base64 = Base64.getEncoder().encodeToString(bytes);
                postEmbeddedCallback("ws-binary", id, () -> onMessage.handle(id, base64, true));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason){
            closed = true;
            removeWsTracking(id);
            if(onClose != null && !suppressCallbacks) postEmbeddedCallback("ws-close", id, () -> onClose.handle(id, statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error){
            closed = true;
            EmbeddedWsConnection connection = removeWsTracking(id);
            if(connection != null){
                try{
                    if(connection.webSocket != null) connection.webSocket.abort();
                }catch(Throwable closeError){
                    YZFErrorLog.low(id, "Embedded WebSocket abort failed", closeError);
                }
            }
            if(onError != null && !suppressCallbacks){
                String message = error == null || error.getMessage() == null ? String.valueOf(error) : error.getMessage();
                postEmbeddedCallback("ws-error", id, () -> onError.handle(id, message));
            }
        }
    }

    private static final class EmbeddedModuleState{
        final YZFModuleDefinition definition;
        final String runtime;
        final Seq<String> serverCommands = new Seq<>();
        final Seq<YZFPlayerCommandBinding> playerCommands = new Seq<>();
        final Seq<YZFEventBinding> eventBindings = new Seq<>();
        final Seq<YZFTaskBinding> taskBindings = new Seq<>();
        final Seq<Runnable> enableCallbacks = new Seq<>();
        final Seq<Runnable> disableCallbacks = new Seq<>();
        URLClassLoader classLoader;

        private EmbeddedModuleState(YZFModuleDefinition definition, String runtime){
            this.definition = definition;
            this.runtime = runtime;
        }

        void runEnable(){
            for(Runnable callback : enableCallbacks){
                YZFCallbackGuard.run(definition.fullId(), "onEnable", callback);
            }
        }

        void runDisable(){
            for(Runnable callback : disableCallbacks){
                YZFCallbackGuard.run(definition.fullId(), "onDisable", callback);
            }
        }
    }
}
