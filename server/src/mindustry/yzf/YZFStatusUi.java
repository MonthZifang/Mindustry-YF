package mindustry.yzf;

import arc.Core;
import arc.util.Strings;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.net.YZFNetworkMetrics;

public final class YZFStatusUi{
    private YZFStatusUi(){
    }

    public static String statusJson(){
        return statusValue().toString(Jval.Jformat.plain);
    }

    public static String uhdStatusUiJson(){
        return uhdStatusUiValue().toString(Jval.Jformat.plain);
    }

    public static Jval statusValue(){
        YZFContext context = MindustryYZF.context();
        YZFNetworkMetrics.sampleNow();

        Jval root = Jval.newObject();
        root.put("ok", true);
        root.put("name", MindustryYZF.name);
        root.put("version", MindustryYZF.version);
        root.put("generatedAtMs", System.currentTimeMillis());
        root.put("runtimeMode", context == null ? "unknown" : context.runtime.mode());
        root.put("watcherRunning", context != null && context.watcher.running());
        root.put("serverOpen", !Vars.state.isMenu());
        root.put("tps", Vars.state.serverTps);
        root.put("players", Groups.player.size());
        root.put("units", Groups.unit.size());
        root.put("enemies", Vars.state.enemies);
        root.put("fps", Core.graphics.getFramesPerSecond());
        root.put("heapMb", Core.app.getJavaHeap() / 1024 / 1024);
        if(context != null){
            Jval memory = Jval.newObject();
            for(java.util.Map.Entry<String, Object> entry : context.memoryRegions.jvmSnapshot().entrySet()){
                Object value = entry.getValue();
                if(value instanceof Number number) memory.put(entry.getKey(), number);
                else if(value instanceof Boolean bool) memory.put(entry.getKey(), bool);
                else memory.put(entry.getKey(), value == null ? null : String.valueOf(value));
            }
            root.put("jvmMemory", memory);
            Jval regions = Jval.newArray();
            for(java.util.Map<String, Object> item : context.memoryRegions.list()){
                Jval region = Jval.newObject();
                for(java.util.Map.Entry<String, Object> entry : item.entrySet()){
                    Object value = entry.getValue();
                    if(value instanceof Number number) region.put(entry.getKey(), number);
                    else if(value instanceof Boolean bool) region.put(entry.getKey(), bool);
                    else region.put(entry.getKey(), value == null ? null : String.valueOf(value));
                }
                regions.add(region);
            }
            root.put("memoryRegions", regions);
        }
        root.put("networkUploadBps", YZFNetworkMetrics.currentUploadBps());
        root.put("networkDownloadBps", YZFNetworkMetrics.currentDownloadBps());
        root.put("syncClientSnapshots", YZFNetworkMetrics.syncClientSnapshots());
        root.put("syncDroppedSnapshots", YZFNetworkMetrics.syncDroppedSnapshots());
        root.put("syncCorrections", YZFNetworkMetrics.syncCorrections());
        root.put("syncRubberbands", YZFNetworkMetrics.syncRubberbands());
        root.put("syncForcedReliableSnapshots", YZFNetworkMetrics.syncForcedReliableSnapshots());
        root.put("syncLastPositionError", YZFNetworkMetrics.syncLastPositionError());

        if(context != null){
            root.put("modules", context.registry.moduleCount());
            root.put("scripts", context.registry.scriptCount());
            root.put("services", context.services.registry().all().size);
            root.put("healthyServices", context.services.registry().healthyCount());
            root.put("commands", context.commandRegistry.all().size);
            Jval features = Jval.newObject();
            for(java.util.Map.Entry<String, Object> entry : context.runtimeConfig.snapshot().entrySet()){
                Object value = entry.getValue();
                if(value instanceof Number number) features.put(entry.getKey(), number);
                else if(value instanceof Boolean bool) features.put(entry.getKey(), bool);
                else features.put(entry.getKey(), value == null ? null : String.valueOf(value));
            }
            root.put("runtimeFeatures", features);
            root.put("pluginCount", pluginCount(context));
            root.put("modulePackageCount", modulePackageCount(context));

            Jval paths = Jval.newObject();
            paths.put("root", ".");
            paths.put("modules", context.paths.relative(context.paths.modulesDir));
            paths.put("plugins", context.paths.relative(context.paths.pluginsDir));
            paths.put("logs", context.paths.relative(context.paths.logsDir));
            paths.put("errorLow", context.paths.relative(context.paths.logsDir.child("errors/low")));
            paths.put("errorMedium", context.paths.relative(context.paths.logsDir.child("errors/medium")));
            paths.put("errorHigh", context.paths.relative(context.paths.logsDir.child("errors/high")));
            paths.put("errorEmergency", context.paths.relative(context.paths.logsDir.child("errors/emergency")));
            paths.put("config", context.paths.relative(context.paths.configDir));
            paths.put("services", context.paths.relative(context.paths.servicesDir));
            paths.put("remotes", context.paths.relative(context.paths.remotesDir));
            paths.put("terminal", context.paths.relative(context.paths.terminalFile));
            root.put("paths", paths);

            if(context.runtime instanceof YZFJsRuntime runtime){
                root.put("loadedModules", runtime.loadedModuleCount());
                root.put("processModules", runtime.processModuleCount());
                root.put("runtimeModules", Jval.read(runtime.runtimeModulesJson()));
            }

            Jval plugins = Jval.newArray();
            for(YZFModuleDefinition module : context.registry.modules()){
                if(!"plugins".equalsIgnoreCase(module.meta._source)) continue;
                Jval plugin = Jval.newObject();
                plugin.put("id", module.fullId());
                plugin.put("name", safeModuleName(module));
                plugin.put("version", YZFText.cleanDisplayText(module.meta.version));
                plugin.put("enabled", module.meta.enabled);
                plugin.put("description", YZFText.cleanDisplayText(module.meta.description));
                plugins.add(plugin);
            }
            root.put("plugins", plugins);
        }

        if(!Vars.state.isMenu() && Vars.state.map != null){
            Jval map = Jval.newObject();
            map.put("name", Strings.capitalize(Vars.state.map.plainName()));
            map.put("wave", Vars.state.wave);
            map.put("waveTimeSeconds", (int)(Vars.state.wavetime / 60f));
            map.put("isPlaying", Vars.state.isPlaying());
            map.put("isPaused", Vars.state.isPaused());
            root.put("map", map);
        }

        Jval players = Jval.newArray();
        Groups.player.each(player -> {
            Jval item = Jval.newObject();
            item.put("id", player.id);
            item.put("name", YZFText.cleanDisplayText(player.plainName()));
            item.put("admin", player.admin());
            item.put("uuid", player.uuid() == null ? "" : player.uuid());
            players.add(item);
        });
        root.put("playerList", players);
        return root;
    }

    public static Jval uhdStatusUiValue(){
        Jval status = statusValue();
        Jval root = Jval.newObject();
        root.put("ok", true);
        root.put("component", "uhd-status-ui");
        root.put("version", 1);
        root.put("generatedAtMs", System.currentTimeMillis());

        Jval navbar = Jval.newObject();
        navbar.put("title", "UHD Status UI");
        navbar.put("subtitle", status.getString("runtimeMode", "unknown"));
        Jval navItems = Jval.newArray();
        navItems.add(navItem("overview", "Overview", true));
        navItems.add(navItem("paths", "Paths", false));
        navbar.put("items", navItems);
        root.put("navbar", navbar);

        Jval window = Jval.newObject();
        window.put("title", "Server Overview");
        window.put("kind", "dashboard");
        window.put("status", status.getBool("serverOpen", false) ? "online" : "offline");
        root.put("window", window);

        Jval metrics = Jval.newArray();
        metrics.add(metric("TPS", String.valueOf(status.getInt("tps", 0)), "server tick rate"));
        metrics.add(metric("Players", String.valueOf(status.getInt("players", 0)), "online players"));
        metrics.add(metric("Net In", formatBytes(status.getLong("networkDownloadBps", 0L)) + "/s", "download throughput"));
        metrics.add(metric("Net Out", formatBytes(status.getLong("networkUploadBps", 0L)) + "/s", "upload throughput"));
        metrics.add(metric("Plugins", String.valueOf(status.getInt("pluginCount", 0)), "plugin-root modules"));
        root.put("metrics", metrics);

        Jval sections = Jval.newArray();

        Jval overview = Jval.newObject();
        overview.put("id", "overview");
        overview.put("title", "Overview");
        overview.put("type", "stats");
        overview.put("data", status);
        sections.add(overview);

        Jval paths = Jval.newObject();
        paths.put("id", "paths");
        paths.put("title", "Paths");
        paths.put("type", "kv");
        paths.put("data", status.get("paths"));
        sections.add(paths);

        root.put("sections", sections);
        root.put("status", status);
        return root;
    }

    private static int pluginCount(YZFContext context){
        int count = 0;
        for(YZFModuleDefinition module : context.registry.modules()){
            if("plugins".equalsIgnoreCase(module.meta._source)) count++;
        }
        return count;
    }

    private static int modulePackageCount(YZFContext context){
        int count = 0;
        for(YZFModuleDefinition module : context.registry.modules()){
            if(!"plugins".equalsIgnoreCase(module.meta._source)) count++;
        }
        return count;
    }

    private static String safeModuleName(YZFModuleDefinition module){
        String name = YZFText.cleanDisplayText(module.meta.name);
        if(!YZFText.blank(name) && !YZFText.looksLikeMojibake(name)) return name;
        String id = YZFText.cleanDisplayText(module.id());
        return YZFText.blank(id) ? module.fullId() : id;
    }

    private static Jval metric(String label, String value, String hint){
        Jval item = Jval.newObject();
        item.put("label", label);
        item.put("value", value);
        item.put("hint", hint);
        return item;
    }

    private static Jval navItem(String id, String label, boolean active){
        Jval item = Jval.newObject();
        item.put("id", id);
        item.put("label", label);
        item.put("active", active);
        return item;
    }

    private static String formatBytes(long bytes){
        if(bytes < 1024L) return bytes + " B";
        if(bytes < 1024L * 1024L) return Strings.fixed(bytes / 1024f, 1) + " KB";
        return Strings.fixed(bytes / 1024f / 1024f, 1) + " MB";
    }
}
