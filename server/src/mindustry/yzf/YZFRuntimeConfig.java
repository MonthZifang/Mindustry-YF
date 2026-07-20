package mindustry.yzf;

import arc.files.Fi;
import arc.util.serialization.Jval;

import java.io.File;

public final class YZFRuntimeConfig{
    public static final String PRECOMPILED = "precompiled";
    public static final String EXTERNAL_KOTLIN = "external-kotlin";
    public static final String EMBEDDED_KOTLIN = "embedded-kotlin";
    public static final String DISABLED = "disabled";

    public final String mode;
    public final boolean precompiledEnabled;
    public final boolean externalKotlinEnabled;
    public final String compilerPath;
    public final String kotlinMode;
    public final String kotlinLibsPath;
    public final String reloadStrategy;
    public final boolean coldLoadEnabled;
    public final String defaultIsolation;
    public final boolean allowPluginCreateRegion;
    public final String regionsConfigPath;
    public final boolean jsEnabled;
    public final boolean nodeEnabled;
    public final boolean javaEnabled;
    public final boolean kotlinRuntimeEnabled;
    public final boolean jsHotReload;
    public final boolean jvmHotReload;
    public final boolean fileWatcherEnabled;
    public final boolean classLoaderIsolationEnabled;
    public final boolean externalNodeEnabled;
    public final boolean errorLoggingEnabled;
    public final boolean errorTerminalColors;
    public final boolean memoryPolicyEnabled;
    public final boolean memoryPolicyForceProcess;
    public final String memoryPolicyDefaultMin;
    public final String memoryPolicyDefaultMax;

    private YZFRuntimeConfig(String mode, boolean precompiledEnabled, boolean externalKotlinEnabled, String compilerPath, String kotlinMode, String kotlinLibsPath, String reloadStrategy, boolean coldLoadEnabled, String defaultIsolation, boolean allowPluginCreateRegion, String regionsConfigPath, boolean jsEnabled, boolean nodeEnabled, boolean javaEnabled, boolean kotlinRuntimeEnabled, boolean jsHotReload, boolean jvmHotReload, boolean fileWatcherEnabled, boolean classLoaderIsolationEnabled, boolean externalNodeEnabled, boolean errorLoggingEnabled, boolean errorTerminalColors, boolean memoryPolicyEnabled, boolean memoryPolicyForceProcess, String memoryPolicyDefaultMin, String memoryPolicyDefaultMax){
        this.mode = mode;
        this.precompiledEnabled = precompiledEnabled;
        this.externalKotlinEnabled = externalKotlinEnabled;
        this.compilerPath = compilerPath;
        this.kotlinMode = kotlinMode;
        this.kotlinLibsPath = kotlinLibsPath;
        this.reloadStrategy = reloadStrategy;
        this.coldLoadEnabled = coldLoadEnabled;
        this.defaultIsolation = defaultIsolation;
        this.allowPluginCreateRegion = allowPluginCreateRegion;
        this.regionsConfigPath = regionsConfigPath;
        this.jsEnabled = jsEnabled;
        this.nodeEnabled = nodeEnabled;
        this.javaEnabled = javaEnabled;
        this.kotlinRuntimeEnabled = kotlinRuntimeEnabled;
        this.jsHotReload = jsHotReload;
        this.jvmHotReload = jvmHotReload;
        this.fileWatcherEnabled = fileWatcherEnabled;
        this.classLoaderIsolationEnabled = classLoaderIsolationEnabled;
        this.externalNodeEnabled = externalNodeEnabled;
        this.errorLoggingEnabled = errorLoggingEnabled;
        this.errorTerminalColors = errorTerminalColors;
        this.memoryPolicyEnabled = memoryPolicyEnabled;
        this.memoryPolicyForceProcess = memoryPolicyForceProcess;
        this.memoryPolicyDefaultMin = memoryPolicyDefaultMin;
        this.memoryPolicyDefaultMax = memoryPolicyDefaultMax;
    }

    public static YZFRuntimeConfig load(YZFPaths paths){
        ensureDefault(paths.runtimeConfigFile);
        try{
            Jval root = Jval.read(paths.runtimeConfigFile.readString());
            String mode = root.getString("mode", PRECOMPILED).trim().toLowerCase();
            if(!mode.equals(PRECOMPILED) && !mode.equals(EXTERNAL_KOTLIN)) mode = PRECOMPILED;
            Jval precompiledNode = root.has("precompiled") ? root.get("precompiled") : null;
            Jval externalNode = root.has("externalKotlin") ? root.get("externalKotlin") : null;
            boolean precompiled = precompiledNode != null && precompiledNode.isObject() ? precompiledNode.getBool("enabled", true) : true;
            boolean external = externalNode != null && externalNode.isObject() && externalNode.getBool("enabled", false);
            String kotlinMode = root.getString("kotlinMode", "").trim().toLowerCase();
            String kotlinLibs = root.getString("kotlinLibsPath", "runtime-sdk/kotlin-libs").trim();
            Jval kotlinNode = root.has("kotlin") ? root.get("kotlin") : null;
            if(kotlinNode != null && kotlinNode.isObject()){
                kotlinMode = kotlinNode.getString("mode", kotlinMode).trim().toLowerCase();
                kotlinLibs = kotlinNode.getString("libsPath", kotlinLibs).trim();
            }
            // Source Kotlin is compiled in-process by default. Precompiled and
            // external modes remain available as explicit compatibility modes.
            if(kotlinMode.isEmpty()) kotlinMode = external ? EXTERNAL_KOTLIN : EMBEDDED_KOTLIN;
            if(!kotlinMode.equals(PRECOMPILED) && !kotlinMode.equals(EMBEDDED_KOTLIN) && !kotlinMode.equals(EXTERNAL_KOTLIN) && !kotlinMode.equals(DISABLED)) kotlinMode = PRECOMPILED;
            String reloadStrategy = root.getString("reloadStrategy", "original").trim().toLowerCase();
            boolean coldLoad = root.getBool("coldLoadEnabled", false);
            String isolation = root.getString("defaultIsolation", "classloader").trim().toLowerCase();
            boolean allowRegions = root.getBool("allowPluginCreateRegion", true);
            String regionsPath = root.getString("regionsConfigPath", "config/memory-regions.hjson").trim();
            Jval coldNode = root.has("coldLoad") ? root.get("coldLoad") : null;
            if(coldNode != null && coldNode.isObject()){
                coldLoad = coldNode.getBool("enabled", coldLoad);
                reloadStrategy = coldNode.getString("reloadStrategy", reloadStrategy).trim().toLowerCase();
                isolation = coldNode.getString("defaultIsolation", isolation).trim().toLowerCase();
                allowRegions = coldNode.getBool("allowPluginCreateRegion", allowRegions);
                regionsPath = coldNode.getString("regionsConfigPath", regionsPath).trim();
            }
            if(!reloadStrategy.equals("original") && !reloadStrategy.equals("cold")) reloadStrategy = "original";
            if(!isolation.equals("classloader") && !isolation.equals("process") && !isolation.equals("logical") && !isolation.equals("auto")) isolation = "classloader";
            Jval features = root.has("features") && root.get("features").isObject() ? root.get("features") : null;
            Jval js = child(features, "js");
            Jval node = child(features, "node");
            Jval java = child(features, "java");
            Jval kotlin = child(features, "kotlin");
            boolean jsEnabled = enabled(js, true);
            boolean nodeEnabled = enabled(node, true);
            boolean javaEnabled = enabled(java, true);
            boolean kotlinEnabled = enabled(kotlin, true);
            boolean jsHotReload = hotReload(js, true);
            boolean jvmHotReload = hotReload(java, true) && hotReload(kotlin, true);
            boolean fileWatcherEnabled = root.getBool("fileWatcherEnabled", true);
            boolean classLoaderIsolationEnabled = root.getBool("classLoaderIsolationEnabled", true);
            boolean externalNodeEnabled = root.getBool("externalNodeEnabled", nodeEnabled);
            Jval errors = root.has("errors") && root.get("errors").isObject() ? root.get("errors") : null;
            boolean errorLoggingEnabled = enabled(child(errors, "logging"), true);
            boolean errorTerminalColors = enabled(child(errors, "terminalColors"), true);
            if(errors != null){
                errorLoggingEnabled = errors.getBool("enabled", errorLoggingEnabled);
                errorTerminalColors = errors.getBool("terminalColors", errorTerminalColors);
            }
            Jval watcher = child(features, "fileWatcher");
            if(watcher != null) fileWatcherEnabled = enabled(watcher, fileWatcherEnabled);
            Jval loader = child(features, "classLoaderIsolation");
            if(loader != null) classLoaderIsolationEnabled = enabled(loader, classLoaderIsolationEnabled);
            Jval memoryPolicy = root.has("memoryPolicy") && root.get("memoryPolicy").isObject() ? root.get("memoryPolicy") : null;
            boolean memoryPolicyEnabled = memoryPolicy != null && memoryPolicy.getBool("enabled", false);
            boolean memoryPolicyForceProcess = memoryPolicy != null && memoryPolicy.getBool("forceProcess", false);
            String memoryPolicyDefaultMin = memoryPolicy == null ? "" : memoryPolicy.getString("defaultMin", "").trim();
            String memoryPolicyDefaultMax = memoryPolicy == null ? "" : memoryPolicy.getString("defaultMax", "").trim();
            return new YZFRuntimeConfig(mode, precompiled, external, root.getString("compilerPath", "").trim(), kotlinMode, kotlinLibs, reloadStrategy, coldLoad, isolation, allowRegions, regionsPath, jsEnabled, nodeEnabled, javaEnabled, kotlinEnabled, jsHotReload, jvmHotReload, fileWatcherEnabled, classLoaderIsolationEnabled, externalNodeEnabled, errorLoggingEnabled, errorTerminalColors, memoryPolicyEnabled, memoryPolicyForceProcess, memoryPolicyDefaultMin, memoryPolicyDefaultMax);
        }catch(Throwable error){
            YZFErrorLog.high("runtime-config", "Failed to parse runtime.hjson; using safe defaults", error);
            return defaults();
        }
    }

    private static YZFRuntimeConfig defaults(){
        return new YZFRuntimeConfig(PRECOMPILED, true, false, "", EMBEDDED_KOTLIN, "runtime-sdk/kotlin-libs", "original", false, "classloader", true, "config/memory-regions.hjson", true, true, true, true, true, true, true, true, true, true, true, false, false, "", "");
    }

    private static Jval child(Jval parent, String key){
        return parent != null && parent.has(key) && parent.get(key).isObject() ? parent.get(key) : null;
    }

    private static boolean enabled(Jval node, boolean fallback){
        return node == null ? fallback : node.getBool("enabled", fallback);
    }

    private static boolean hotReload(Jval node, boolean fallback){
        return node == null ? fallback : node.getBool("hotReload", fallback);
    }

    public boolean runtimeEnabled(String runtime){
        if(runtime == null || runtime.isBlank() || runtime.equalsIgnoreCase("js")) return jsEnabled;
        return switch(runtime.toLowerCase()){
            case "node" -> nodeEnabled && externalNodeEnabled;
            case "java" -> javaEnabled;
            case "kt", "kts" -> kotlinRuntimeEnabled;
            default -> false;
        };
    }

    public boolean hotReloadEnabled(String runtime){
        if(runtime == null || runtime.isBlank() || runtime.equalsIgnoreCase("js")) return jsHotReload;
        return switch(runtime.toLowerCase()){
            case "java", "kt", "kts" -> jvmHotReload;
            default -> false;
        };
    }

    public boolean memoryPolicyApplies(String runtime, String configuredMin, String configuredMax){
        if(runtime == null) return false;
        String value = runtime.toLowerCase();
        boolean processCapable = value.equals("node") || value.equals("java") || value.equals("kt") || value.equals("kts");
        if(!processCapable || !memoryPolicyEnabled) return false;
        return memoryPolicyForceProcess || !YZFText.blank(configuredMin) || !YZFText.blank(configuredMax) || !YZFText.blank(memoryPolicyDefaultMin) || !YZFText.blank(memoryPolicyDefaultMax);
    }

    public String effectiveMemoryMin(String configured){
        return YZFText.blank(configured) && memoryPolicyEnabled ? memoryPolicyDefaultMin : configured;
    }

    public String effectiveMemoryMax(String configured){
        return YZFText.blank(configured) && memoryPolicyEnabled ? memoryPolicyDefaultMax : configured;
    }

    public java.util.Map<String, Object> snapshot(){
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("jsEnabled", jsEnabled);
        result.put("nodeEnabled", nodeEnabled);
        result.put("javaEnabled", javaEnabled);
        result.put("kotlinEnabled", kotlinRuntimeEnabled);
        result.put("jsHotReload", jsHotReload);
        result.put("jvmHotReload", jvmHotReload);
        result.put("fileWatcherEnabled", fileWatcherEnabled);
        result.put("classLoaderIsolationEnabled", classLoaderIsolationEnabled);
        result.put("externalNodeEnabled", externalNodeEnabled);
        result.put("errorLoggingEnabled", errorLoggingEnabled);
        result.put("errorTerminalColors", errorTerminalColors);
        result.put("memoryPolicyEnabled", memoryPolicyEnabled);
        result.put("memoryPolicyForceProcess", memoryPolicyForceProcess);
        result.put("memoryPolicyDefaultMin", memoryPolicyDefaultMin);
        result.put("memoryPolicyDefaultMax", memoryPolicyDefaultMax);
        result.put("kotlinMode", kotlinMode);
        result.put("kotlinLibsPath", kotlinLibsPath);
        result.put("reloadStrategy", reloadStrategy);
        return result;
    }

    public boolean externalKotlin(){
        return EXTERNAL_KOTLIN.equals(kotlinMode) && (externalKotlinEnabled || EXTERNAL_KOTLIN.equals(mode));
    }

    public boolean embeddedKotlin(){
        return EMBEDDED_KOTLIN.equals(kotlinMode);
    }

    public boolean kotlinEnabled(){
        return !DISABLED.equals(kotlinMode);
    }

    private static void ensureDefault(Fi file){
        if(file.exists()) return;
        file.parent().mkdirs();
        file.writeString("{\n" +
            "  // precompiled: load Kotlin jars built during development\n" +
            "  // kotlin.mode: precompiled | embedded-kotlin | external-kotlin | disabled\n" +
            "  mode: \"precompiled\"\n" +
            "  precompiled: { enabled: true }\n" +
            "  externalKotlin: { enabled: false }\n" +
            "  kotlin: { mode: \"embedded-kotlin\", libsPath: \"runtime-sdk/kotlin-libs\" }\n" +
            "  # Production switches. Set enabled/hotReload false for unused runtimes.\n" +
            "  features: { js: { enabled: true, hotReload: true }, node: { enabled: false, hotReload: false }, java: { enabled: false, hotReload: false }, kotlin: { enabled: false, hotReload: false }, fileWatcher: { enabled: false }, classLoaderIsolation: { enabled: false } }\n" +
            "  fileWatcherEnabled: false\n" +
            "  classLoaderIsolationEnabled: false\n" +
            "  externalNodeEnabled: false\n" +
            "  errors: { enabled: true, terminalColors: true }\n" +
            "  memoryPolicy: { enabled: false, forceProcess: false, defaultMin: \"\", defaultMax: \"\" }\n" +
            "  coldLoad: { enabled: false, reloadStrategy: \"original\", defaultIsolation: \"classloader\", allowPluginCreateRegion: true, regionsConfigPath: \"config/memory-regions.hjson\" }\n" +
            "  // Optional absolute path to kotlinc or kotlinc.bat. Empty means search PATH/KOTLIN_HOME.\n" +
            "  compilerPath: \"runtime-sdk/kotlin/bin/kotlinc.bat\"\n" +
            "}\n");
    }

    public String resolveCompiler(YZFPaths paths){
        if(!compilerPath.isEmpty()){
            File file = new File(compilerPath);
            if(!file.isAbsolute()) file = new File(paths.root.file(), compilerPath);
            if(file.isFile()) return file.getAbsolutePath();
        }
        return null;
    }
}
