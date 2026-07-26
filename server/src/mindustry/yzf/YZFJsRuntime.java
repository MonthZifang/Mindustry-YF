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
import mindustry.gen.Player;
import mindustry.mod.Scripts;
import rhino.Context;
import rhino.Function;
import rhino.Scriptable;
import rhino.ScriptableObject;

import java.nio.file.Path;

public final class YZFJsRuntime implements YZFScriptRuntime{
    private final YZFModuleRegistry registry;
    private final ObjectMap<String, YZFLoadedModule> loadedModules = new ObjectMap<>();
    private final ObjectMap<String, String> commandOwners = new ObjectMap<>();
    private final ObjectMap<String, String> playerCommandOwners = new ObjectMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Function> runtimeServerCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Function> runtimePlayerCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final YZFProcessRuntime processRuntime = new YZFProcessRuntime();
    private final YZFEmbeddedRuntime embeddedRuntime = new YZFEmbeddedRuntime();
    private YZFCompatibilityMiddleware compatibilityMiddleware;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingReloadRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean reloadDispatchScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean reloadAllRequested;
    private Scripts scripts;
    private static final long reloadDebounceMs = 750L;

    public YZFJsRuntime(YZFModuleRegistry registry){
        this.registry = registry;
    }


    @Override
    public synchronized void reloadAll(){
        MindustryYZF.context().metrics.moduleReloads.incrementAndGet();
        MindustryYZF.context().metrics.markReload();
        registry.scan();
        ensureScripts();

        Seq<String> validIds = new Seq<>();
        for(YZFModuleDefinition module : registry.modules()){
            validIds.add(module.fullId());
            execute(module);
        }

        Seq<String> toRemove = new Seq<>();
        for(String id : loadedModules.keys()){
            if(!validIds.contains(id)){
                toRemove.add(id);
            }
        }
        for(String id : processRuntime.moduleIds()){
            if(!validIds.contains(id) && !toRemove.contains(id)){
                toRemove.add(id);
            }
        }
        for(String id : toRemove){
            unloadModule(id, true);
        }
    }

    @Override
    public synchronized void reloadModule(String moduleId){
        MindustryYZF.context().metrics.moduleReloads.incrementAndGet();
        MindustryYZF.context().metrics.markReload();
        registry.scan();
        ensureScripts();

        Seq<YZFModuleDefinition> plan = registry.resolveReloadPlan(moduleId);
        if(plan.isEmpty()){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("module-not-found:" + moduleId);
            Log.err("[@] 鎵句笉鍒版ā鍧? @", MindustryYZF.name, moduleId);
            return;
        }

        ObjectMap<String, YZFLoadedModule> previousJsStates = new ObjectMap<>();
        YZFReloadSnapshot snapshot = createSnapshot(plan, previousJsStates);

        for(int i = plan.size - 1; i >= 0; i--){
            YZFModuleDefinition planned = plan.get(i);
            String plannedRuntime = YZFText.blank(planned.meta.runtime)
                ? "js"
                : planned.meta.runtime.trim().toLowerCase();

            // Kotlin compilation is performed by the embedded/external Kotlin
            // runtime before it stops the current ClassLoader. Unloading here
            // would discard the working version before a syntax error can be
            // reported, defeating failure-safe hot reload.
            if(plannedRuntime.equals("kt") || plannedRuntime.equals("kts")){
                continue;
            }
            unloadModule(planned.fullId(), true);
        }

        Seq<YZFModuleDefinition> applied = new Seq<>();
        try{
            for(YZFModuleDefinition module : plan){
                if(!execute(module)){
                    throw new IllegalStateException("Module load failed: " + module.fullId());
                }
                applied.add(module);
            }
        }catch(Throwable t){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.moduleRollbacks.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("reload-rollback:" + moduleId + ": " + t.getMessage());
            MindustryYZF.context().audit.record("module-rollback", moduleId, YZFSecurity.sanitizeLog(t.getMessage()));
            Log.err("[@] transactional reload failed, restoring previous module state.", MindustryYZF.name, t);

            for(int i = applied.size - 1; i >= 0; i--){
                unloadModule(applied.get(i).fullId(), true);
            }
            for(YZFModuleDefinition module : snapshot.modules){
                if(!snapshot.loadedIds.contains(module.fullId())) continue;
                YZFLoadedModule loaded = previousJsStates.get(module.fullId());
                if(loaded != null){
                    executeSource(loaded.definition, loaded.sourceText);
                }else{
                    execute(module);
                }
            }
        }
    }

    @Override
    public synchronized void onFileChange(Path path){
        if(path != null && path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase("runtime.hjson")){
            MindustryYZF.context().reloadRuntimeConfig();
            if(MindustryYZF.context().runtimeConfig.fileWatcherEnabled){
                requestReloadAll();
            }
            return;
        }
        String moduleId = resolveModuleId(path);
        if(moduleId != null){
            YZFModuleDefinition module = registry.find(moduleId);
            String runtime = module == null ? "js" : module.meta.runtime;
            String fileName = path == null || path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
            boolean metadataChange = fileName.equals("module.hjson") || fileName.equals("module.json");
            boolean memoryPolicyChange = metadataChange && module != null &&
                (!YZFText.blank(module.meta.memoryMin) || !YZFText.blank(module.meta.memoryMax) || processRuntime.running(moduleId));
            if(MindustryYZF.context().runtimeConfig.fileWatcherEnabled &&
                (MindustryYZF.context().runtimeConfig.hotReloadEnabled(runtime) || memoryPolicyChange)){
                requestReloadModule(moduleId, memoryPolicyChange);
            }
        }else{
            if(MindustryYZF.context().runtimeConfig.fileWatcherEnabled){
                requestReloadAll();
            }
        }
    }

    @Override
    public synchronized void shutdown(){
        for(String id : loadedModules.keys().toSeq()){
            unloadModule(id, true);
        }
        processRuntime.stopAll();
        embeddedRuntime.stopAll();
        if(scripts != null){
            // Shutdown hooks run outside the game thread; Scripts.dispose requires a Rhino context.
            rhino.Context.enter();
            scripts.dispose();
            scripts = null;
        }
    }

    @Override
    public String mode(){
        return "JavaScript + Embedded Runtimes";
    }

    synchronized Seq<String> loadedModuleIds(){
        Seq<String> result = loadedModules.keys().toSeq();
        for(String id : processRuntime.moduleIds()){
            if(!result.contains(id)) result.add(id);
        }
        for(String id : embeddedRuntime.moduleIds()){
            if(!result.contains(id)) result.add(id);
        }
        return result;
    }

    synchronized int loadedModuleCount(){
        return loadedModules.size + processRuntime.size() + embeddedRuntime.size();
    }

    synchronized YZFLoadedModule getLoadedModule(String moduleId){
        return loadedModules.get(moduleId);
    }

    Object callEmbeddedExport(String targetModuleId, String fnName, Object[] args){
        return embeddedRuntime.callExportedFunction(targetModuleId, fnName, args);
    }

    synchronized int processModuleCount(){
        return processRuntime.size() + embeddedRuntime.size();
    }

    public synchronized boolean terminateModule(String moduleId){
        if(YZFText.blank(moduleId)) return false;
        boolean present = loadedModules.containsKey(moduleId) || processRuntime.running(moduleId) || embeddedRuntime.definition(moduleId) != null;
        unloadModule(moduleId, true);
        return present;
    }

    public synchronized boolean setModuleMemoryLimits(String moduleId, String minHeap, String maxHeap){
        YZFModuleDefinition module = registry.find(moduleId);
        if(module == null) return false;
        long min = YZFMemoryRegionManager.parseBytes(minHeap);
        long max = YZFMemoryRegionManager.parseBytes(maxHeap);
        if(min > 0 && max > 0 && min > max) throw new IllegalArgumentException("memoryMin cannot exceed memoryMax");
        module.meta.memoryMin = minHeap == null ? "" : minHeap.trim();
        module.meta.memoryMax = maxHeap == null ? "" : maxHeap.trim();
        YZFModuleIO.writeMeta(module);
        registry.scan();
        // Memory policy changes are always applied immediately. A process
        // must be restarted so new -Xms/-Xmx or Node heap flags take effect;
        // no cooperation from the plugin is required.
        if(processRuntime.running(moduleId) || embeddedRuntime.definition(moduleId) != null || loadedModules.containsKey(moduleId)){
            reloadModule(moduleId);
        }
        return true;
    }

    synchronized String runtimeModulesJson(){
        Jval root = Jval.newObject();
        Jval embedded = Jval.newArray();
        for(java.util.Map<String, Object> item : embeddedRuntime.snapshot()) embedded.add(mapJson(item));
        Jval processes = Jval.newArray();
        for(java.util.Map<String, Object> item : processRuntime.snapshot()) processes.add(mapJson(item));
        root.put("embedded", embedded);
        root.put("processes", processes);
        return root.toString(Jval.Jformat.plain);
    }

    private Jval mapJson(java.util.Map<String, Object> map){
        Jval result = Jval.newObject();
        for(java.util.Map.Entry<String, Object> entry : map.entrySet()){
            Object value = entry.getValue();
            if(value instanceof Number number) result.put(entry.getKey(), number);
            else if(value instanceof Boolean bool) result.put(entry.getKey(), bool);
            else result.put(entry.getKey(), value == null ? null : String.valueOf(value));
        }
        return result;
    }

    synchronized ObjectMap<String, String> commandOwners(){
        return commandOwners;
    }

    synchronized ObjectMap<String, String> playerCommandOwners(){
        return playerCommandOwners;
    }

    synchronized boolean invokeServerCommand(String commandName, String[] args){
        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        for(CommandHandler.Command cmd : handler.getCommandList()){
            if(cmd.text.equalsIgnoreCase(commandName)){
                try{
                    StringBuilder sb = new StringBuilder(commandName);
                    for(String a : args){
                        sb.append(' ').append(a);
                    }
                    handler.handleMessage(sb.toString());
                    return true;
                }catch(Throwable t){
                    Log.err("[@] 璋冪敤鍛戒护 '@' 澶辫触: @", MindustryYZF.name, commandName, t.getMessage());
                    return false;
                }
            }
        }
        return false;
    }

    private void ensureScripts(){
        if(scripts == null){
            scripts = Vars.platform.createScripts();
        }
    }

    private YZFReloadSnapshot createSnapshot(Seq<YZFModuleDefinition> plan, ObjectMap<String, YZFLoadedModule> previousJsStates){
        Seq<YZFModuleDefinition> snapshotModules = new Seq<>();
        Seq<String> loadedIds = new Seq<>();

        for(YZFModuleDefinition module : plan){
            String id = module.fullId();
            YZFLoadedModule loaded = loadedModules.get(id);
            if(loaded != null){
                previousJsStates.put(id, loaded);
                snapshotModules.add(loaded.definition);
                loadedIds.add(id);
                continue;
            }

            YZFModuleDefinition processDefinition = processRuntime.definition(id);
            if(processDefinition != null){
                snapshotModules.add(processDefinition);
                loadedIds.add(id);
                continue;
            }

            YZFModuleDefinition embeddedDefinition = embeddedRuntime.definition(id);
            if(embeddedDefinition != null){
                snapshotModules.add(embeddedDefinition);
                loadedIds.add(id);
                continue;
            }

            snapshotModules.add(module);
        }

        return registry.snapshot(snapshotModules, loadedIds);
    }

    private boolean execute(YZFModuleDefinition module){
        String moduleRuntime = YZFText.blank(module.meta.runtime) ? "js" : module.meta.runtime.trim().toLowerCase();
        // Kotlin is prepared before the old ClassLoader is stopped; keep the old version on compile failure.
        if(!moduleRuntime.equals("kt") && !moduleRuntime.equals("kts")){
            unloadModule(module.fullId(), true);
        }

        if(!module.meta.enabled){
            unloadModule(module.fullId(), true);
            Log.info("[@] 璺宠繃宸茬鐢ㄦā鍧? @", MindustryYZF.name, module.fullId());
            return true;
        }
        if(!module.hasMain()){
            Log.warn("[@] 妯″潡缂哄皯涓昏剼鏈? @ -> @", MindustryYZF.name, module.fullId(), module.meta.main);
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("missing-main:" + module.fullId());
            return false;
        }

        String configuredRuntime = YZFText.blank(module.meta.runtime) ? "js" : module.meta.runtime.trim().toLowerCase();
        if(!MindustryYZF.context().runtimeConfig.runtimeEnabled(configuredRuntime)){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("runtime-disabled:" + module.fullId() + ": " + configuredRuntime);
            YZFErrorLog.low(module.fullId(), "Runtime disabled by production configuration: " + configuredRuntime, null);
            return false;
        }
        boolean memoryPolicy = MindustryYZF.context().runtimeConfig.memoryPolicyApplies(configuredRuntime, module.meta.memoryMin, module.meta.memoryMax);
        if((configuredRuntime.equals("java") || configuredRuntime.equals("kt") || configuredRuntime.equals("kts")) && !MindustryYZF.context().runtimeConfig.classLoaderIsolationEnabled && !MindustryYZF.context().runtimeConfig.defaultIsolation.equals("process") && !memoryPolicy){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("classloader-isolation-disabled:" + module.fullId());
            YZFErrorLog.low(module.fullId(), "Java/Kotlin module blocked because ClassLoader isolation is disabled", null);
            return false;
        }
        if(!MindustryYZF.context().securityConfig.allows(configuredRuntime)){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("runtime-denied:" + module.fullId() + ": " + configuredRuntime);
            MindustryYZF.context().audit.record("module-load-denied", module.fullId(), configuredRuntime);
            Log.err("[@] Runtime is not allowed by security configuration: @ -> @", MindustryYZF.name, module.fullId(), configuredRuntime);
            return false;
        }

        if(module.meta.runtime != null && !module.meta.runtime.equalsIgnoreCase("js")){
            String runtime = configuredRuntime;
            try{
                if(runtime.equals("node")){
                    if(!MindustryYZF.context().securityConfig.allowsProcess(runtime)){
                        throw new IllegalStateException("Process runtimes are disabled by security configuration.");
                    }
                    processRuntime.reload(module);
                }else if((runtime.equals("java") || runtime.equals("kt") || runtime.equals("kts")) && (MindustryYZF.context().runtimeConfig.defaultIsolation.equals("process") || memoryPolicy)){
                    processRuntime.reload(module);
                }else if(runtime.equals("java") && MindustryYZF.context().runtimeConfig.precompiledEnabled){
                    embeddedRuntime.reload(module);
                }else if((runtime.equals("kt") || runtime.equals("kts")) && MindustryYZF.context().runtimeConfig.embeddedKotlin()){
                    embeddedRuntime.reload(module);
                }else if((runtime.equals("kt") || runtime.equals("kts")) && MindustryYZF.context().runtimeConfig.externalKotlin()){
                    embeddedRuntime.reloadCompiledJar(module, YZFExternalKotlinCompiler.compile(module));
                }else if(runtime.equals("kt") || runtime.equals("kts")){
                    throw new IllegalStateException("Kotlin runtime is disabled or set to precompiled; use a compiled jar or set kotlin.mode.");
                }else{
                    throw new IllegalStateException("Unsupported embedded runtime: " + runtime);
                }
                MindustryYZF.context().metrics.moduleLoads.incrementAndGet();
                MindustryYZF.context().audit.record("module-load", module.fullId(), module.meta.runtime);
                return true;
            }catch(Throwable t){
                MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
                MindustryYZF.context().metrics.markFailure("module-load:" + module.fullId() + ": " + t.getMessage());
                MindustryYZF.context().audit.record("module-load-failed", module.fullId(), YZFSecurity.sanitizeLog(t.getMessage()));
                Log.err("[@] Module execution failed: @", MindustryYZF.name, module.fullId(), t);
                return false;
            }
        }

        return executeSource(module, YZFText.readTextSmart(module.mainScript));
    }

    private boolean executeSource(YZFModuleDefinition module, String sourceText){
        Context ctx = Context.enter();
        YZFJsModuleBridge bridge = null;
        try{
            Scriptable moduleScope = ctx.newObject(scripts.scope);
            moduleScope.setPrototype(scripts.scope);
            moduleScope.setParentScope(null);
            bridge = injectModuleContext(module, moduleScope, ctx);
            compatibilityMiddleware().apply(ctx, moduleScope, module);
            ctx.evaluateString(moduleScope, sourceText, module.mainScript.absolutePath(), 1);
            YZFLoadedModule state = bridge.freeze(sourceText);
            loadedModules.put(module.fullId(), state);
            invokeLifecycleStrict(state.onEnable, state.scope);
            MindustryYZF.context().metrics.moduleLoads.incrementAndGet();
            MindustryYZF.context().audit.record("module-load", module.fullId(), "js");
            Log.info("[@] Module loaded: @", MindustryYZF.name, module.fullId());
            return true;
        }catch(Throwable t){
            MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
            MindustryYZF.context().metrics.markFailure("module-load:" + module.fullId() + ": " + t.getMessage());
            Log.err("[@] Module execution failed: @", MindustryYZF.name, module.fullId());
            Log.err(t);
            if(loadedModules.containsKey(module.fullId())){
                unloadModule(module.fullId(), false);
            }else if(bridge != null){
                bridge.discard();
            }
            return false;
        }finally{
            Context.exit();
        }
    }

    private YZFJsModuleBridge injectModuleContext(YZFModuleDefinition module, Scriptable moduleScope, Context ctx){
        YZFJsModuleBridge bridge = new YZFJsModuleBridge(this, module, moduleScope);
        ScriptableObject.putProperty(moduleScope, "__yzfBridge", Context.javaToJS(bridge, moduleScope));

        String _id = escape(module.id());
        String _fullId = escape(module.fullId());
        String _name = escape(module.meta.name);
        String _author = escape(module.author());
        String _version = escape(module.meta.version);
        String _root = escape(module.root.absolutePath());
        String _scriptsDir = escape(module.scriptsDir.absolutePath());
        String _dataDir = escape(module.dataDir.absolutePath());
        String _cacheDir = escape(module.cacheDir.absolutePath());

        String command =
            "var yzfModule = {\n" +
            "  id: \"" + _id + "\",\n" +
            "  fullId: \"" + _fullId + "\",\n" +
            "  name: \"" + _name + "\",\n" +
            "  author: \"" + _author + "\",\n" +
            "  version: \"" + _version + "\",\n" +
            "  root: \"" + _root + "\",\n" +
            "  scriptsDir: \"" + _scriptsDir + "\",\n" +
            "  dataDir: \"" + _dataDir + "\",\n" +
            "  cacheDir: \"" + _cacheDir + "\"\n" +
            "};\n" +
            "var yzf = {\n" +
            "  command: function(name, a, b, c){\n" +
            "    if(arguments.length === 3){ return __yzfBridge.command3(String(name), String(a), b); }\n" +
            "    if(arguments.length === 4){ return __yzfBridge.command4(String(name), String(a), String(b), c); }\n" +
            "    throw 'yzf.command 鍙傛暟閿欒';\n" +
            "  },\n" +
            "  playerCommand: function(name, usage, description, fn){ return __yzfBridge.playerCommand4(String(name), String(usage), String(description), fn); },\n" +
            "  adminCommand: function(name, usage, description, permission, fn){ return __yzfBridge.adminCommand4(String(name), String(usage), String(description), String(permission), fn); },\n" +
            "  on: function(eventName, fn){ return __yzfBridge.onEvent(String(eventName), fn); },\n" +
            "  after: function(delaySeconds, fn){ return __yzfBridge.after(Number(delaySeconds), fn); },\n" +
            "  every: function(delaySeconds, intervalSeconds, fn){ return __yzfBridge.every(Number(delaySeconds), Number(intervalSeconds), fn); },\n" +
            "  onEnable: function(fn){ return __yzfBridge.onEnable(fn); },\n" +
            "  onDisable: function(fn){ return __yzfBridge.onDisable(fn); },\n" +
            "  ui: {\n" +
            "    registerPage: function(pageId, descriptor){ return __yzfBridge.uiRegisterPage(String(pageId), typeof descriptor === 'string' ? descriptor : JSON.stringify(descriptor || {})); },\n" +
            "    unregisterPage: function(pageId){ return __yzfBridge.uiUnregisterPage(String(pageId)); }\n" +
            "  },\n" +
            "  config: {\n" +
            "    get: function(key, def){ return __yzfBridge.configGet(String(key), def == null ? '' : String(def)); },\n" +
            "    getBool: function(key, def){ return __yzfBridge.configGetBool(String(key), !!def); },\n" +
            "    getInt: function(key, def){ return __yzfBridge.configGetInt(String(key), Number(def)); },\n" +
            "    set: function(key, value){ return __yzfBridge.configSet(String(key), String(value)); },\n" +
            "    setBool: function(key, value){ return __yzfBridge.configSetBool(String(key), !!value); },\n" +
            "    setInt: function(key, value){ return __yzfBridge.configSetInt(String(key), Number(value)); },\n" +
            "    path: function(){ return __yzfBridge.configPath(); }\n" +
            "  },\n" +
            "  remote: {\n" +
            "    get: function(serviceId, path){ return __yzfBridge.httpGet(String(serviceId), String(path)); },\n" +
            "    postJson: function(serviceId, path, body){ return __yzfBridge.httpPostJson(String(serviceId), String(path), String(body)); }\n" +
            "  },\n" +
            "  service: {\n" +
            "    has: function(serviceId){ return __yzfBridge.hasService(String(serviceId)); },\n" +
            "    summary: function(serviceId){ return __yzfBridge.serviceSummary(String(serviceId)); },\n" +
            "    list: function(){ return __yzfBridge.serviceList(); },\n" +
            "    info: function(serviceId){ return __yzfBridge.serviceInfo(String(serviceId)); },\n" +
            "    call: function(serviceId, action, a, b, c){\n" +
            "      if(arguments.length === 2){ return __yzfBridge.serviceCall2(String(serviceId), String(action)); }\n" +
            "      if(arguments.length === 3){ return __yzfBridge.serviceCall3(String(serviceId), String(action), String(a)); }\n" +
            "      if(arguments.length === 4){ return __yzfBridge.serviceCall4(String(serviceId), String(action), String(a), String(b)); }\n" +
            "      if(arguments.length === 5){ return __yzfBridge.serviceCall5(String(serviceId), String(action), String(a), String(b), String(c)); }\n" +
            "      throw 'yzf.service.call 鍙傛暟閿欒';\n" +
            "    }\n" +
            "  },\n" +
            "  runtime: {\n" +
            "    mode: function(){ return __yzfBridge.runtimeMode(); },\n" +
            "    config: function(){ return JSON.parse(__yzfBridge.runtimeConfig()); },\n" +
            "    modules: function(){ return JSON.parse(__yzfBridge.runtimeModules()); },\n" +
            "    terminate: function(moduleId){ return __yzfBridge.runtimeTerminate(String(moduleId)); },\n" +
            "    setMemory: function(moduleId, minHeap, maxHeap){ return __yzfBridge.runtimeSetMemory(String(moduleId), String(minHeap || ''), String(maxHeap || '')); },\n" +
            "    watcherRunning: function(){ return __yzfBridge.watcherRunning(); },\n" +
            "    reloadSelf: function(){ return __yzfBridge.requestReloadSelf(); },\n" +
            "    reloadModule: function(moduleId){ return __yzfBridge.requestReloadModule(String(moduleId)); },\n" +
            "    reloadAll: function(){ return __yzfBridge.requestReloadAll(); }\n" +
            "  },\n" +
            "  memory: {\n" +
            "    jvm: function(){ return JSON.parse(__yzfBridge.memoryJvm()); },\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.memoryList()); },\n" +
            "    info: function(id){ var r = __yzfBridge.memoryInfo(String(id)); return r ? JSON.parse(r) : null; },\n" +
            "    create: function(id, mode, minHeap, maxHeap){ return JSON.parse(__yzfBridge.memoryCreate(String(id), String(mode || 'logical'), String(minHeap || ''), String(maxHeap || ''))); },\n" +
            "    load: function(regionId, jarPath, className){ return __yzfBridge.memoryLoad(String(regionId), String(jarPath), className == null ? '' : String(className)); },\n" +
            "    stop: function(id){ return __yzfBridge.memoryStop(String(id)); }\n" +
            "  },\n" +
            "  openapi: {\n" +
            "    manifest: function(){ return JSON.parse(__yzfBridge.openApiManifest()); },\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.openApiList()); },\n" +
            "    info: function(capabilityId){ var r = __yzfBridge.openApiInfo(String(capabilityId)); return r ? JSON.parse(r) : null; },\n" +
            "    summary: function(){ return JSON.parse(__yzfBridge.openApiSummary()); },\n" +
            "    readOnly: function(){ return JSON.parse(__yzfBridge.openApiReadOnly()); },\n" +
            "    writeOnly: function(){ return JSON.parse(__yzfBridge.openApiWriteOnly()); }\n" +
            "  },\n" +
            "  status: {\n" +
            "    snapshot: function(){ return JSON.parse(__yzfBridge.statusSnapshot()); },\n" +
            "    ui: function(){ return JSON.parse(__yzfBridge.uhdStatusUi()); }\n" +
            "  },\n" +
            "  response: {\n" +
            "    ok: function(code, message, data){ return { ok: true, success: true, code: code || 'ok', message: message || '', data: data === undefined ? null : data, timestampMs: Date.now() }; },\n" +
            "    fail: function(code, message, data){ return { ok: false, success: false, code: code || 'error', message: message || '', data: data === undefined ? null : data, timestampMs: Date.now() }; }\n" +
            "  },\n" +
            "  redis: {\n" +
            "    get: function(serviceId, key){ return __yzfBridge.redisGet(String(serviceId), String(key)); },\n" +
            "    set: function(serviceId, key, value){ return __yzfBridge.redisSet(String(serviceId), String(key), String(value)); },\n" +
            "    del: function(serviceId, key){ return __yzfBridge.redisDelete(String(serviceId), String(key)); },\n" +
            "    incr: function(serviceId, key){ return __yzfBridge.redisIncrement(String(serviceId), String(key)); },\n" +
            "    hget: function(serviceId, key, field){ return __yzfBridge.redisHashGet(String(serviceId), String(key), String(field)); },\n" +
            "    hset: function(serviceId, key, field, value){ return __yzfBridge.redisHashSet(String(serviceId), String(key), String(field), String(value)); }\n" +
            "  },\n" +
            "  sql: {\n" +
            "    queryFirstCell: function(serviceId, sql){ return __yzfBridge.sqlQueryFirstCell(String(serviceId), String(sql)); },\n" +
            "    execute: function(serviceId, sql){ return __yzfBridge.sqlExecute(String(serviceId), String(sql)); },\n" +
            "    queryJson: function(serviceId, sql){ return __yzfBridge.sqlQueryJson(String(serviceId), String(sql)); }\n" +
            "  },\n" +
            "  minio: {\n" +
            "    putText: function(serviceId, objectName, text){ return __yzfBridge.minioPutText(String(serviceId), String(objectName), String(text)); }\n" +
            "  },\n" +
            "  log: function(msg){ return __yzfBridge.log(String(msg)); },\n" +
            "  info: function(msg){ return __yzfBridge.info(String(msg)); },\n" +
            "  warn: function(msg){ return __yzfBridge.warn(String(msg)); },\n" +
            "  err: function(msg){ return __yzfBridge.err(String(msg)); },\n" +
            "  evalFile: function(path){ return __yzfBridge.evalFile(String(path)); },\n" +
            "  stableApi: function(id){ return __yzfBridge.stableApi(String(id)); },\n" +
            "  stableApiManifest: function(){ return __yzfBridge.stableApiManifest(); },\n" +
            "  player: {\n" +
            "    kick: function(id, reason, duration){\n" +
            "      if(duration !== undefined) return __yzfBridge.playerKickDuration(Number(id), String(reason), Number(duration));\n" +
            "      return __yzfBridge.playerKick(Number(id), String(reason || ''));\n" +
            "    },\n" +
            "    ban: function(id){ return __yzfBridge.playerBan(Number(id)); },\n" +
            "    banIP: function(ip){ return __yzfBridge.playerBanIP(String(ip)); },\n" +
            "    banID: function(uuidOrComid){ return __yzfBridge.playerBanID(String(uuidOrComid)); },\n" +
            "    unbanIP: function(ip){ return __yzfBridge.playerUnbanIP(String(ip)); },\n" +
            "    unbanID: function(uuidOrComid){ return __yzfBridge.playerUnbanID(String(uuidOrComid)); },\n" +
            "    admin: function(id, isAdmin){ return __yzfBridge.playerAdmin(Number(id), !!isAdmin); },\n" +
            "    adminByComid: function(comid, isAdmin){ return __yzfBridge.playerAdminComid(Number(comid), !!isAdmin); },\n" +
            "    info: function(id){ var r = __yzfBridge.playerInfo(Number(id)); return r ? JSON.parse(r) : null; },\n" +
            "    infoByComid: function(comid){ var r = __yzfBridge.playerInfoByComid(Number(comid)); return r ? JSON.parse(r) : null; },\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.playerList()); },\n" +
            "    find: function(nameOrId){ var r = __yzfBridge.playerFind(String(nameOrId)); return r ? JSON.parse(r) : null; },\n" +
            "    send: function(id, msg){ return __yzfBridge.playerSend(Number(id), String(msg)); },\n" +
            "    count: function(){ return __yzfBridge.playerCount(); }\n" +
            "  },\n" +
            "  game: {\n" +
            "    wave: function(){ return __yzfBridge.gameWave(); },\n" +
            "    setWave: function(n){ return __yzfBridge.gameSetWave(Number(n)); },\n" +
            "    waveTime: function(){ return __yzfBridge.gameWaveTime(); },\n" +
            "    setWaveTime: function(t){ return __yzfBridge.gameSetWaveTime(Number(t)); },\n" +
            "    skipWave: function(){ return __yzfBridge.gameSkipWave(); },\n" +
            "    tick: function(){ return __yzfBridge.gameTick(); },\n" +
            "    tps: function(){ return __yzfBridge.gameTps(); },\n" +
            "    map: function(){ return JSON.parse(__yzfBridge.gameMap()); },\n" +
            "    isPlaying: function(){ return __yzfBridge.gameIsPlaying(); },\n" +
            "    isPaused: function(){ return __yzfBridge.gameIsPaused(); },\n" +
            "    isCampaign: function(){ return __yzfBridge.gameIsCampaign(); },\n" +
            "    isPvp: function(){ return __yzfBridge.gameIsPvp(); },\n" +
            "    isAttack: function(){ return __yzfBridge.gameIsAttack(); },\n" +
            "    enemies: function(){ return __yzfBridge.gameEnemies(); },\n" +
            "    rules: function(){ return JSON.parse(__yzfBridge.gameRules()); },\n" +
            "    setRule: function(key, value){ return __yzfBridge.gameSetRule(String(key), String(value)); }\n" +
            "  },\n" +
            "  net: {\n" +
            "    send: function(id, msg){ return __yzfBridge.netSend(Number(id), String(msg)); },\n" +
            "    broadcast: function(msg, senderId){\n" +
            "      if(senderId !== undefined) return __yzfBridge.netBroadcastFrom(String(msg), Number(senderId));\n" +
            "      return __yzfBridge.netBroadcast(String(msg));\n" +
            "    }\n" +
            "  },\n" +
            "  content: {\n" +
            "    block: function(name){ var r = __yzfBridge.contentBlock(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    item: function(name){ var r = __yzfBridge.contentItem(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    liquid: function(name){ var r = __yzfBridge.contentLiquid(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    unit: function(name){ var r = __yzfBridge.contentUnit(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    status: function(name){ var r = __yzfBridge.contentStatus(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    weather: function(name){ var r = __yzfBridge.contentWeather(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    planet: function(name){ var r = __yzfBridge.contentPlanet(String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    blocks: function(){ return JSON.parse(__yzfBridge.contentBlocks()); },\n" +
            "    items: function(){ return JSON.parse(__yzfBridge.contentItems()); },\n" +
            "    liquids: function(){ return JSON.parse(__yzfBridge.contentLiquids()); },\n" +
            "    units: function(){ return JSON.parse(__yzfBridge.contentUnits()); },\n" +
            "    registerMeta: function(namespace, name, json){ return __yzfBridge.contentRegisterMeta(String(namespace), String(name), typeof json === 'object' ? JSON.stringify(json) : String(json)); },\n" +
            "    getMeta: function(namespace, name){ var r = __yzfBridge.contentGetMeta(String(namespace), String(name)); return r ? JSON.parse(r) : null; },\n" +
            "    listMeta: function(namespace){ return JSON.parse(__yzfBridge.contentListMeta(String(namespace))); },\n" +
            "    listNamespaces: function(){ return JSON.parse(__yzfBridge.contentListNamespaces()); },\n" +
            "    removeMeta: function(namespace, name){ return __yzfBridge.contentRemoveMeta(String(namespace), String(name)); },\n" +
            "    setProperty: function(contentName, property, value){ return JSON.parse(__yzfBridge.contentSetProperty(String(contentName), String(property), String(value))); },\n" +
            "    getProperty: function(contentName, property){ return __yzfBridge.contentGetProperty(String(contentName), String(property)); }\n" +
            "  },\n" +
            "  world: {\n" +
            "    spawn: function(type, id, x, y, size, teamId, buff){\n" +
            "      if(size === undefined) size = 1;\n" +
            "      if(teamId === undefined) teamId = 1;\n" +
            "      return JSON.parse(__yzfBridge.worldSpawn(String(type), String(id), Number(x), Number(y), Number(size), Number(teamId), buff == null ? null : String(buff)));\n" +
            "    },\n" +
            "    batchSpawn: function(ops){ return JSON.parse(__yzfBridge.worldBatchSpawn(typeof ops === 'string' ? ops : JSON.stringify(ops))); },\n" +
            "    fill: function(itemId, amount, teamId){\n" +
            "      if(teamId === undefined) teamId = 1;\n" +
            "      return JSON.parse(__yzfBridge.worldFill(String(itemId), Number(amount), Number(teamId)));\n" +
            "    }\n" +
            "  },\n" +
            "  ws: {\n" +
            "    connect: function(url, onOpen, onMessage, onClose, onError){ return __yzfBridge.wsConnect(String(url), onOpen, onMessage, onClose, onError); },\n" +
            "    send: function(id, msg){ return __yzfBridge.wsSend(String(id), String(msg)); },\n" +
            "    sendBinary: function(id, base64){ return __yzfBridge.wsSendBinary(String(id), String(base64)); },\n" +
            "    close: function(id){ return __yzfBridge.wsClose(String(id)); },\n" +
            "    isOpen: function(id){ return __yzfBridge.wsIsOpen(String(id)); },\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.wsList()); }\n" +
            "  },\n" +
            "  comid: {\n" +
            "    get: function(uuid){ var r = __yzfBridge.comidGet(String(uuid)); return r < 0 ? null : r; },\n" +
            "    getOrCreate: function(uuid){ return __yzfBridge.comidGetOrCreate(String(uuid)); },\n" +
            "    uuid: function(comid){ return __yzfBridge.comidGetUuid(Number(comid)); },\n" +
            "    exists: function(comid){ return __yzfBridge.comidExists(Number(comid)); },\n" +
            "    digits: function(){ return __yzfBridge.comidDigits(); },\n" +
            "    remaining: function(){ return __yzfBridge.comidRemaining(); },\n" +
            "    total: function(){ return __yzfBridge.comidTotal(); }\n" +
            "  },\n" +
            "  data: {\n" +
            "    get: function(comid, key, def){ var r = __yzfBridge.playerDataGet(Number(comid), String(key)); return r !== null ? r : (def !== undefined ? String(def) : null); },\n" +
            "    set: function(comid, key, value){ return __yzfBridge.playerDataSet(Number(comid), String(key), String(value)); },\n" +
            "    getInt: function(comid, key, def){ return __yzfBridge.playerDataGetInt(Number(comid), String(key), Number(def || 0)); },\n" +
            "    setInt: function(comid, key, value){ return __yzfBridge.playerDataSetInt(Number(comid), String(key), Number(value)); },\n" +
            "    getBool: function(comid, key, def){ return __yzfBridge.playerDataGetBool(Number(comid), String(key), !!def); },\n" +
            "    setBool: function(comid, key, value){ return __yzfBridge.playerDataSetBool(Number(comid), String(key), !!value); },\n" +
            "    getDouble: function(comid, key, def){ return __yzfBridge.playerDataGetDouble(Number(comid), String(key), Number(def || 0)); },\n" +
            "    setDouble: function(comid, key, value){ return __yzfBridge.playerDataSetDouble(Number(comid), String(key), Number(value)); },\n" +
            "    all: function(comid){ return JSON.parse(__yzfBridge.playerDataGetAll(Number(comid))); },\n" +
            "    remove: function(comid, key){ return __yzfBridge.playerDataRemove(Number(comid), String(key)); },\n" +
            "    clear: function(comid){ return __yzfBridge.playerDataClear(Number(comid)); }\n" +
            "  },\n" +
            "  db: {\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.dbList()); },\n" +
            "    info: function(id){ var r = __yzfBridge.dbInfo(String(id)); return r ? JSON.parse(r) : null; },\n" +
            "    has: function(id){ return __yzfBridge.dbHas(String(id)); },\n" +
            "    addLocal: function(id, name){ return __yzfBridge.dbAddLocal(String(id), name == null ? '' : String(name)); },\n" +
            "    addRemote: function(id, name, endpoint, serviceId, readOnly){ return __yzfBridge.dbAddRemote(String(id), name == null ? '' : String(name), endpoint == null ? '' : String(endpoint), serviceId == null ? '' : String(serviceId), !!readOnly); },\n" +
            "    remove: function(id){ return __yzfBridge.dbRemove(String(id)); },\n" +
            "    categories: function(id){ return JSON.parse(__yzfBridge.dbCategories(String(id))); },\n" +
            "    keys: function(id, category){ return JSON.parse(__yzfBridge.dbKeys(String(id), String(category))); },\n" +
            "    get: function(id, category, key, def){ var r = __yzfBridge.dbGet(String(id), String(category), String(key)); return r !== null && r !== undefined ? r : (def !== undefined ? def : null); },\n" +
            "    set: function(id, category, key, value){ return __yzfBridge.dbSet(String(id), String(category), String(key), typeof value === 'object' ? JSON.stringify(value) : String(value)); },\n" +
            "    removeEntry: function(id, category, key){ return __yzfBridge.dbRemoveEntry(String(id), String(category), String(key)); },\n" +
            "    dump: function(id){ return JSON.parse(__yzfBridge.dbDump(String(id))); },\n" +
            "    import: function(id, json){ return __yzfBridge.dbImport(String(id), typeof json === 'string' ? json : JSON.stringify(json)); },\n" +
            "    defaultId: function(){ return __yzfBridge.dbDefaultId(); },\n" +
            "    count: function(){ return __yzfBridge.dbCount(); }\n" +
            "  },\n" +
            "  commands: {\n" +
            "    register: function(name, description, fn){ return __yzfBridge.commandsRegister(String(name), String(description), fn, null); },\n" +
            "    unregister: function(name){ return __yzfBridge.commandsUnregister(String(name)); },\n" +
            "    has: function(name){ return __yzfBridge.commandsHas(String(name)); },\n" +
            "    call: function(name, a, b, c, d, e){\n" +
            "      var args = [];\n" +
            "      for(var i = 1; i < arguments.length; i++) args.push(arguments[i]);\n" +
            "      return __yzfBridge.commandsCall(String(name), args);\n" +
            "    },\n" +
            "    run: function(commandName, a, b, c, d, e){\n" +
            "      var arr = [];\n" +
            "      for(var i = 1; i < arguments.length; i++) arr.push(String(arguments[i]));\n" +
            "      return __yzfBridge.commandsRun(String(commandName), arr);\n" +
            "    },\n" +
            "    list: function(){ return JSON.parse(__yzfBridge.commandsList()); },\n" +
            "    listModule: function(moduleId){ return JSON.parse(__yzfBridge.commandsListModule(String(moduleId))); }\n" +
            "  },\n" +
            "  module: {\n" +
            "    list: function(){ return __yzfBridge.moduleList(); },\n" +
            "    info: function(moduleId){ return __yzfBridge.moduleInfo(String(moduleId)); },\n" +
            "    export: function(fnName, fn){ return __yzfBridge.moduleExport(String(fnName), fn); },\n" +
            "    call: function(moduleId, fnName, a, b, c, d, e){\n" +
            "      var args = [];\n" +
            "      for(var i = 2; i < arguments.length; i++) args.push(arguments[i]);\n" +
            "      return __yzfBridge.moduleCall(String(moduleId), String(fnName), args);\n" +
            "    },\n" +
            "    exported: function(moduleId){ return JSON.parse(__yzfBridge.moduleExportedFunctions(String(moduleId))); }\n" +
            "  },\n" +
            "  mod: {\n" +
            "    registerServerCommand: function(name, usage, desc, fn){ return __yzfBridge.modRegisterServerCommand(String(name), String(usage), String(desc), fn); },\n" +
            "    registerPlayerCommand: function(name, usage, desc, fn){ return __yzfBridge.modRegisterPlayerCommand(String(name), String(usage), String(desc), false, '', fn); },\n" +
            "    registerAdminCommand: function(name, usage, desc, perm, fn){ return __yzfBridge.modRegisterPlayerCommand(String(name), String(usage), String(desc), true, String(perm), fn); },\n" +
            "    registerCallableCommand: function(name, desc, fn){ return __yzfBridge.modRegisterCallableCommand(String(name), String(desc), fn); },\n" +
            "    unregisterCommand: function(name){ return __yzfBridge.modUnregisterCommand(String(name)); },\n" +
            "    listCommands: function(){ return JSON.parse(__yzfBridge.modListCommands()); },\n" +
            "    hasCommand: function(name){ return __yzfBridge.modHasCommand(String(name)); }\n" +
            "  }\n" +
            "};\n";
        ctx.evaluateString(moduleScope, command, module.mainScript.absolutePath() + "#yzf-bootstrap", 1);
        return bridge;
    }

    private YZFCompatibilityMiddleware compatibilityMiddleware(){
        if(compatibilityMiddleware == null){
            compatibilityMiddleware = new YZFCompatibilityMiddleware(MindustryYZF.context().paths.compatDir);
        }
        return compatibilityMiddleware;
    }

    private String resolveModuleId(Path path){
        String normalized = path.toString().replace('\\', '/');
        for(YZFModuleDefinition module : registry.modules()){
            String root = module.root.absolutePath().replace('\\', '/');
            if(normalized.startsWith(root)){
                return module.fullId();
            }
        }
        return null;
    }

    private String escape(String value){
        if(value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    synchronized void registerModuleCommand(YZFModuleDefinition module, String commandName, String usage, String description, Function callback){
        String owner = commandOwners.get(commandName);
        if(owner != null && !owner.equals(module.fullId())){
            throw new IllegalStateException("command '" + commandName + "' is already owned by module " + owner);
        }
        if(owner == null && hasNonYZFCommand(commandName)){
            throw new IllegalStateException("command '" + commandName + "' conflicts with an existing server command");
        }

        String moduleId = module.fullId();
        if(callback != null){
            runtimeServerCallbacks.put(commandName, callback);
        }

        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        handler.removeCommand(commandName);
        handler.register(commandName, usage == null ? "" : usage, description == null ? "" : description, args -> {
            Function fn = runtimeServerCallbacks.get(commandName);
            if(fn != null){
                invokeCommand(moduleId, fn, args);
            }
        });
        commandOwners.put(commandName, moduleId);
    }

    synchronized void registerPlayerCommand(YZFModuleDefinition module, String commandName, String usage, String description, boolean adminOnly, String permission, Function callback){
        String owner = playerCommandOwners.get(commandName);
        if(owner != null && !owner.equals(module.fullId())){
            throw new IllegalStateException("player command '" + commandName + "' is already owned by module " + owner);
        }

        String moduleId = module.fullId();
        if(callback != null){
            runtimePlayerCallbacks.put(commandName, callback);
        }

        CommandHandler handler = Vars.netServer.clientCommands;
        handler.removeCommand(commandName);
        handler.<Player>register(commandName, usage == null ? "" : usage, description == null ? "" : description, (args, player) -> {
            if(adminOnly && (player == null || !player.admin)){
                if(player != null) player.sendMessage("[scarlet]This command is admin-only.");
                return;
            }
            if(!YZFText.blank(permission) && !MindustryYZF.context().permissions.has(player, permission)){
                MindustryYZF.context().metrics.permissionDenied.incrementAndGet();
                MindustryYZF.context().audit.record("permission-denied", module.fullId(), commandName + " -> " + permission);
                if(player != null) player.sendMessage("[scarlet]You do not have permission to use this command.");
                return;
            }
            Function fn = runtimePlayerCallbacks.get(commandName);
            if(fn != null){
                invokePlayerCommand(moduleId, fn, player, args);
            }
        });
        playerCommandOwners.put(commandName, moduleId);
    }

    /** 璁剧疆杩愯鏃跺懡浠ゅ洖璋?(鐢ㄤ簬鎺у埗鍙版敞鍐屽悗鐢辨ā鍧楁彁渚涘疄鐜? */
    synchronized void setRuntimeCommandCallback(String commandName, Function callback){
        runtimeServerCallbacks.put(commandName, callback);
    }

    /** 璁剧疆杩愯鏃剁帺瀹跺懡浠ゅ洖璋?*/
    synchronized void setRuntimePlayerCommandCallback(String commandName, Function callback){
        runtimePlayerCallbacks.put(commandName, callback);
    }

    synchronized YZFEventBinding registerModuleEvent(YZFModuleDefinition module, Scriptable scope, String eventName, Function callback){
        Class<?> eventType = YZFEventRegistry.find(eventName);
        if(eventType == null){
            throw new IllegalArgumentException("鏈煡浜嬩欢: " + eventName);
        }
        Cons<Object> handler = event -> invokeEvent(scope, callback, event);
        Events.on((Class)eventType, (Cons)handler);
        return new YZFEventBinding(eventName, eventType, handler);
    }

    private boolean hasNonYZFCommand(String commandName){
        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        for(CommandHandler.Command command : handler.getCommandList()){
            if(command.text.equalsIgnoreCase(commandName)){
                return commandOwners.get(commandName) == null;
            }
        }
        return false;
    }

    private void invokeCommand(String moduleId, Function callback, String[] args){
        if(callback == null) return;
        ensureScripts();
        YZFLoadedModule state = loadedModules.get(moduleId);
        if(state == null){
            Log.err("[@] 鏃犳硶璋冪敤鍛戒护锛屾ā鍧楁湭澶勪簬宸插姞杞界姸鎬? @", MindustryYZF.name, moduleId);
            return;
        }
        Context ctx = Context.enter();
        try{
            Object[] objArgs = new Object[args.length];
            System.arraycopy(args, 0, objArgs, 0, args.length);
            Scriptable jsArgs = ctx.newArray(state.scope, objArgs);
            callback.call(ctx, state.scope, state.scope, new Object[]{jsArgs});
            MindustryYZF.context().metrics.serverCommandCalls.incrementAndGet();
        }catch(Throwable t){
            Log.err("[@] 鍛戒护鍥炶皟鎵ц澶辫触: @ (妯″潡: @)", MindustryYZF.name, "unknown", moduleId, t);
        }finally{
            Context.exit();
        }
    }

    private void invokePlayerCommand(String moduleId, Function callback, Player player, String[] args){
        if(callback == null) return;
        ensureScripts();
        YZFLoadedModule state = loadedModules.get(moduleId);
        if(state == null) return;
        Context ctx = Context.enter();
        try{
            Object[] objArgs = new Object[args.length];
            System.arraycopy(args, 0, objArgs, 0, args.length);
            Scriptable jsArgs = ctx.newArray(state.scope, objArgs);
            callback.call(ctx, state.scope, state.scope, new Object[]{player, jsArgs});
            MindustryYZF.context().metrics.playerCommandCalls.incrementAndGet();
        }catch(Throwable t){
            Log.err("[@] 鐜╁鍛戒护鍥炶皟鎵ц澶辫触: @ (妯″潡: @)", MindustryYZF.name, "unknown", moduleId, t);
        }finally{
            Context.exit();
        }
    }

    private void invokeEvent(Scriptable scope, Function callback, Object event){
        ensureScripts();
        Context ctx = Context.enter();
        try{
            callback.call(ctx, scope, scope, new Object[]{event});
        }catch(Throwable t){
            Log.err("[@] 浜嬩欢鍥炶皟鎵ц澶辫触: @", MindustryYZF.name, t);
        }finally{
            Context.exit();
        }
    }

    synchronized Timer.Task scheduleOnce(Scriptable scope, float delaySeconds, Function callback){
        return Timer.schedule(() -> {
            Context ctx = Context.enter();
            try{
                YZFCallbackGuard.run("unknown", "js-timer-once", () -> callback.call(ctx, scope, scope, new Object[0]));
            }finally{
                Context.exit();
            }
        }, delaySeconds);
    }

    synchronized Timer.Task scheduleRepeating(Scriptable scope, float delaySeconds, float intervalSeconds, Function callback){
        return Timer.schedule(() -> {
            Context ctx = Context.enter();
            try{
                YZFCallbackGuard.run("unknown", "js-timer-repeat", () -> callback.call(ctx, scope, scope, new Object[0]));
            }finally{
                Context.exit();
            }
        }, delaySeconds, intervalSeconds);
    }

    synchronized void requestReloadModule(String moduleId){
        requestReloadModule(moduleId, false);
    }

    private synchronized void requestReloadModule(String moduleId, boolean force){
        if(YZFText.blank(moduleId) || MindustryYZF.isShuttingDown()) return;
        YZFModuleDefinition module = registry.find(moduleId);
        if(!force && module != null && !MindustryYZF.context().runtimeConfig.hotReloadEnabled(module.meta.runtime)) return;
        long now = System.currentTimeMillis();
        Long last = pendingReloadRequests.put(moduleId, now);
        if(last != null && now - last < reloadDebounceMs){
            return;
        }
        scheduleReloadDispatch();
    }

    synchronized void requestReloadAll(){
        if(MindustryYZF.isShuttingDown()) return;
        reloadAllRequested = true;
        pendingReloadRequests.clear();
        scheduleReloadDispatch();
    }

    private void scheduleReloadDispatch(){
        if(!reloadDispatchScheduled.compareAndSet(false, true)) return;
        Timer.schedule(this::drainReloadRequests, 0.05f);
    }

    private void drainReloadRequests(){
        if(MindustryYZF.isShuttingDown()){
            synchronized(this){
                reloadDispatchScheduled.set(false);
                pendingReloadRequests.clear();
                reloadAllRequested = false;
            }
            return;
        }
        Seq<String> modules = new Seq<>();
        boolean reloadAll;
        synchronized(this){
            reloadDispatchScheduled.set(false);
            reloadAll = reloadAllRequested;
            reloadAllRequested = false;
            for(String moduleId : pendingReloadRequests.keySet()){
                modules.add(moduleId);
            }
            pendingReloadRequests.clear();
        }

        if(reloadAll){
            reloadAll();
            return;
        }

        for(String moduleId : modules){
            reloadModule(moduleId);
        }
    }

    private void invokeLifecycle(Function callback, Scriptable scope){
        if(callback == null) return;
        ensureScripts();
        Context ctx = Context.enter();
        try{
            callback.call(ctx, scope, scope, new Object[0]);
        }catch(Throwable t){
            Log.err("[@] 鐢熷懡鍛ㄦ湡鍥炶皟鎵ц澶辫触: @", MindustryYZF.name, t.getMessage());
            Log.err(t);
        }finally{
            Context.exit();
        }
    }

    private void invokeLifecycleStrict(Function callback, Scriptable scope){
        if(callback == null) return;
        ensureScripts();
        Context ctx = Context.enter();
        try{
            callback.call(ctx, scope, scope, new Object[0]);
        }finally{
            Context.exit();
        }
    }

    synchronized void discardModuleResources(String moduleId, Seq<String> commandNames, Seq<YZFPlayerCommandBinding> playerCommands, Seq<YZFEventBinding> eventBindings, Seq<YZFTaskBinding> taskBindings){
        cleanupModuleResources(moduleId, commandNames, playerCommands, eventBindings, taskBindings);
    }

    private synchronized void unloadModule(String moduleId, boolean runDisable){
        YZFLoadedModule state = loadedModules.remove(moduleId);
        processRuntime.stop(moduleId);
        embeddedRuntime.stop(moduleId);
        if(state == null) return;

        if(runDisable){
            try{
                invokeLifecycle(state.onDisable, state.scope);
            }catch(Throwable t){
                Log.err("[@] 妯″潡 onDisable 鎵ц澶辫触: @", MindustryYZF.name, moduleId, t);
            }
        }

        cleanupModuleResources(moduleId, state.commandNames, state.playerCommandNames, state.eventBindings, state.taskBindings);
        MindustryYZF.context().audit.record("module-unload", moduleId, state.definition.meta.runtime);
    }

    private void cleanupModuleResources(String moduleId, Seq<String> commandNames, Seq<YZFPlayerCommandBinding> playerCommands, Seq<YZFEventBinding> eventBindings, Seq<YZFTaskBinding> taskBindings){
        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        for(String commandName : commandNames){
            if(moduleId.equals(commandOwners.get(commandName))){
                handler.removeCommand(commandName);
                commandOwners.remove(commandName);
                runtimeServerCallbacks.remove(commandName);
            }
        }
        for(YZFPlayerCommandBinding binding : playerCommands){
            if(moduleId.equals(playerCommandOwners.get(binding.name))){
                Vars.netServer.clientCommands.removeCommand(binding.name);
                playerCommandOwners.remove(binding.name);
                runtimePlayerCallbacks.remove(binding.name);
            }
        }
        for(YZFEventBinding binding : eventBindings){
            Events.remove((Class)binding.eventType, (Cons)binding.handler);
        }
        for(YZFTaskBinding binding : taskBindings){
            binding.cancel();
        }
        YZFJsModuleBridge.clearExportedFunctions(moduleId);
        MindustryYZF.context().webUi.unregisterModule(moduleId);
        YZFJsModuleBridge.cleanupModuleWs(moduleId);
        MindustryYZF.context().commandRegistry.clearModule(moduleId);
    }
}
