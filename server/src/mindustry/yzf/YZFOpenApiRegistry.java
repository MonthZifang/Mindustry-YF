package mindustry.yzf;

import arc.struct.Seq;
import arc.util.serialization.Jval;

/**
 * Builds a stable, queryable capability manifest for external callers.
 * The output is intentionally read/write separated so third-party modules can
 * discover what is safe to call before they start wiring against the runtime.
 */
public final class YZFOpenApiRegistry{
    private YZFOpenApiRegistry(){
    }

    public static String manifestJson(){
        return buildManifest().toString(Jval.Jformat.plain);
    }

    public static String listJson(){
        return groupsToJson(groups()).toString(Jval.Jformat.plain);
    }

    public static String readOnlyJson(){
        return groupsToJson(filterGroups("read")).toString(Jval.Jformat.plain);
    }

    public static String writeOnlyJson(){
        return groupsToJson(filterGroups("write")).toString(Jval.Jformat.plain);
    }

    public static String infoJson(String id){
        CapabilityGroup group = find(id);
        if(group == null){
            Jval err = Jval.newObject();
            err.put("ok", false);
            err.put("error", "Unknown capability group: " + String.valueOf(id));
            return err.toString(Jval.Jformat.plain);
        }
        return groupToJson(group).toString(Jval.Jformat.plain);
    }

    public static String summaryJson(){
        return buildSummary().toString(Jval.Jformat.plain);
    }

    public static String statusJson(){
        return YZFStatusUi.statusJson();
    }

    public static String uhdStatusUiJson(){
        return YZFStatusUi.uhdStatusUiJson();
    }

    private static Jval buildManifest(){
        Jval root = Jval.newObject();
        root.put("ok", true);
        root.put("name", MindustryYZF.name);
        root.put("version", MindustryYZF.version);
        root.put("generatedAtMs", System.currentTimeMillis());
        root.put("mode", safeRuntimeMode());
        root.put("supportedRuntimes", runtimeList());
        root.put("supportedScriptExtensions", scriptExtensionList());
        root.put("summary", buildSummary());

        Jval groups = Jval.newArray();
        for(CapabilityGroup group : groups()){
            groups.add(groupToJson(group));
        }
        root.put("groups", groups);
        root.put("readOnly", groupsToJson(filterGroups("read")));
        root.put("writeOnly", groupsToJson(filterGroups("write")));
        return root;
    }

    private static Jval buildSummary(){
        Seq<CapabilityGroup> all = groups();
        int readGroups = 0;
        int writeGroups = 0;
        int mixedGroups = 0;
        int readMethods = 0;
        int writeMethods = 0;
        int totalMethods = 0;
        for(CapabilityGroup group : all){
            if("read".equals(group.access)) readGroups++;
            else if("write".equals(group.access)) writeGroups++;
            else mixedGroups++;
            for(MethodSpec method : group.methods){
                totalMethods++;
                if("write".equals(method.access)) writeMethods++;
                else readMethods++;
            }
        }

        YZFContext context = safeContext();
        int moduleCount = context == null ? 0 : context.registry.moduleCount();
        int pluginCount = 0;
        int moduleDirCount = 0;
        if(context != null){
            for(YZFModuleDefinition module : context.registry.modules()){
                if("plugins".equalsIgnoreCase(module.meta._source)) pluginCount++;
                else moduleDirCount++;
            }
        }

        int serviceCount = context == null ? 0 : context.services.registry().all().size;
        int healthyServices = context == null ? 0 : context.services.registry().healthyCount();
        int commandCount = context == null ? 0 : context.commandRegistry.all().size;
        int wsCount = context == null ? 0 : context.wsManager.listConnections().length;
        int roleCount = context == null ? 0 : context.permissions.roles().size;
        int auditLines = context == null ? 0 : context.audit.tail(20).size;
        int namespaceCount = 0;
        if(context != null){
            try{
                Jval namespaces = Jval.read(context.contentRegistry.listNamespaces());
                if(namespaces.isArray()){
                    for(@SuppressWarnings("unused") Jval ignored : namespaces.asArray()){
                        namespaceCount++;
                    }
                }
            }catch(Exception ignored){
                namespaceCount = 0;
            }
        }

        Jval root = Jval.newObject();
        root.put("groups", all.size);
        root.put("methods", totalMethods);
        root.put("readGroups", readGroups);
        root.put("writeGroups", writeGroups);
        root.put("mixedGroups", mixedGroups);
        root.put("readMethods", readMethods);
        root.put("writeMethods", writeMethods);
        root.put("modules", moduleCount);
        root.put("modulePackages", moduleDirCount);
        root.put("plugins", pluginCount);
        root.put("services", serviceCount);
        root.put("healthyServices", healthyServices);
        root.put("databases", context == null ? 0 : context.databaseRegistry.count());
        root.put("commands", commandCount);
        root.put("websocketConnections", wsCount);
        root.put("permissionRoles", roleCount);
        root.put("auditTailSize", auditLines);
        root.put("contentNamespaces", namespaceCount);
        root.put("runtimeMode", safeRuntimeMode());
        root.put("supportedRuntimes", runtimeList());
        root.put("supportedScriptExtensions", scriptExtensionList());
        root.put("status", YZFStatusUi.statusValue());
        return root;
    }

    private static Jval groupsToJson(Seq<CapabilityGroup> source){
        Jval array = Jval.newArray();
        for(CapabilityGroup group : source){
            array.add(groupToJson(group));
        }
        return array;
    }

    private static Jval groupToJson(CapabilityGroup group){
        Jval root = Jval.newObject();
        root.put("id", group.id);
        root.put("title", group.title);
        root.put("access", group.access);
        root.put("summary", group.summary);
        root.put("permission", group.permission);
        root.put("risk", group.risk);
        root.put("sideEffects", group.sideEffects);
        Jval methods = Jval.newArray();
        for(MethodSpec method : group.methods){
            Jval m = Jval.newObject();
            m.put("name", method.name);
            m.put("access", method.access);
            m.put("description", method.description);
            m.put("risk", method.risk);
            m.put("sideEffects", method.sideEffects);
            methods.add(m);
        }
        root.put("methods", methods);
        return root;
    }

    private static Seq<CapabilityGroup> filterGroups(String access){
        Seq<CapabilityGroup> filtered = new Seq<>();
        for(CapabilityGroup group : groups()){
            if(access.equals(group.access)) filtered.add(group);
        }
        return filtered;
    }

    private static CapabilityGroup find(String id){
        if(YZFText.blank(id)) return null;
        for(CapabilityGroup group : groups()){
            if(group.id.equalsIgnoreCase(id.trim())) return group;
        }
        return null;
    }

    private static YZFContext safeContext(){
        try{
            return MindustryYZF.context();
        }catch(Exception ignored){
            return null;
        }
    }

    private static String safeRuntimeMode(){
        YZFContext context = safeContext();
        if(context == null || context.runtime == null) return "unknown";
        try{
            return context.runtime.mode();
        }catch(Exception ignored){
            return "unknown";
        }
    }

    private static Jval runtimeList(){
        Jval array = Jval.newArray();
        for(String runtime : YZFModuleLoader.supportedRuntimes()){
            array.add(runtime);
        }
        return array;
    }

    private static Jval scriptExtensionList(){
        Jval array = Jval.newArray();
        for(String extension : YZFModuleLoader.supportedScriptExtensions()){
            array.add(extension);
        }
        return array;
    }

    private static Seq<CapabilityGroup> groups(){
        Seq<CapabilityGroup> groups = new Seq<>();

        groups.add(group(
            "openapi",
            "Developer Open API",
            "read",
            "Manifest, list, info and summary endpoints used to discover what this server can expose.",
            "none",
            method("manifest", "read", "Return the full capability manifest."),
            method("list", "read", "Return all capability groups."),
            method("info", "read", "Return a single capability group by id."),
            method("summary", "read", "Return a compact capability summary."),
            method("readOnly", "read", "Return read-only capability groups."),
            method("writeOnly", "read", "Return write-only capability groups.")
        ));

        groups.add(group(
            "server.command",
            "Server Console Commands",
            "read",
            "Console-side management entrypoints available through the yzf command family.",
            "admin / console",
            method("help", "read", "Show the command help list."),
            method("status", "read", "Show server and layout status."),
            method("health", "read", "Show dependency and service health."),
            method("metrics", "read", "Show runtime metrics and counters."),
            method("scan", "write", "Rescan the module directory."),
            method("watch", "write", "Control file watcher lifecycle."),
            method("reload", "write", "Reload one or all modules."),
            method("modules", "read", "List discovered modules."),
            method("info", "read", "Inspect one module."),
            method("enable", "write", "Enable a module."),
            method("disable", "write", "Disable a module."),
            method("plugins", "read", "List plugin-root modules."),
            method("plugin", "write", "Enable or disable a plugin module."),
            method("services", "read", "List configured services."),
            method("service", "write", "Inspect or test a service."),
            method("permissions", "write", "Reload or inspect permissions."),
            method("runtime", "read", "Inspect runtime state."),
            method("audit", "read", "Read the audit log tail."),
            method("verify", "read", "Print a verification summary."),
            method("api", "read", "Inspect the open API registry.")
        ));

        groups.add(group(
            "bridge.config",
            "Configuration Bridge",
            "mixed",
            "Read and write module-local configuration values and the resolved config path.",
            "module scope",
            method("get", "read", "Read a string config value."),
            method("getBool", "read", "Read a boolean config value."),
            method("getInt", "read", "Read an integer config value."),
            method("set", "write", "Persist a string config value."),
            method("setBool", "write", "Persist a boolean config value."),
            method("setInt", "write", "Persist an integer config value."),
            method("path", "read", "Return the config file path.")
        ));

        groups.add(group(
            "status.ui",
            "UHD Status UI",
            "read",
            "Structured server snapshot and reusable UI description for dashboards, navbars and windows.",
            "none",
            method("status", "read", "Return the structured status snapshot."),
            method("ui", "read", "Return the reusable UHD Status UI description.")
        ));

        groups.add(group(
            "bridge.service",
            "Service Bridge",
            "mixed",
            "Inspect and call registered service clients such as SQL, Redis, MinIO and remote HTTP services.",
            "service access",
            method("has", "read", "Check whether a service exists."),
            method("summary", "read", "Read a service summary string."),
            method("list", "read", "List registered services."),
            method("info", "read", "Inspect a single service."),
            method("call", "write", "Call a service action with up to three arguments.")
        ));

        groups.add(group(
            "bridge.runtime",
            "Runtime Bridge",
            "mixed",
            "Inspect the current runtime and request safe delayed reload operations.",
            "module scope",
            method("mode", "read", "Read the current runtime mode."),
            method("config", "read", "Read production runtime switches and Kotlin mode."),
            method("watcherRunning", "read", "Check whether the file watcher is running."),
            method("modules", "read", "Read active embedded modules and external process memory snapshots."),
            method("terminate", "write", "Terminate one module/process and clean its registered resources."),
            method("setMemory", "write", "Set per-module process memory minimum and maximum and reload the process."),
            method("reloadSelf", "write", "Request a delayed reload for the current module."),
            method("reloadModule", "write", "Request a delayed reload for a specific module."),
            method("reloadAll", "write", "Request a delayed full reload.")
        ));

        groups.add(group(
            "bridge.memory",
            "YF Memory Regions",
            "mixed",
            "Query JVM memory and create or stop YF1/YF2 logical, ClassLoader, or Process regions.",
            "module scope / configured plugin access",
            method("jvm", "read", "Read current JVM heap, non-heap and startup arguments."),
            method("list", "read", "List all memory regions."),
            method("info", "read", "Inspect one memory region."),
            method("create", "write", "Create a logical, ClassLoader, or Process region."),
            method("stop", "write", "Stop and destroy a region; YF1 cannot be stopped.")
        ));

        groups.add(group(
            "bridge.remote",
            "Remote HTTP Bridge",
            "write",
            "Perform remote HTTP GET/POST calls through a registered remote service.",
            "service access",
            method("get", "write", "Send a remote HTTP GET request."),
            method("postJson", "write", "Send a remote HTTP POST with JSON body.")
        ));

        groups.add(group(
            "bridge.redis",
            "Redis Bridge",
            "write",
            "Interact with Redis-compatible service clients.",
            "service access",
            method("get", "write", "Read a value from Redis."),
            method("set", "write", "Set a Redis string value."),
            method("del", "write", "Delete a Redis key."),
            method("incr", "write", "Increment a Redis integer."),
            method("hget", "write", "Read a Redis hash field."),
            method("hset", "write", "Write a Redis hash field.")
        ));

        groups.add(group(
            "bridge.sql",
            "SQL Bridge",
            "write",
            "Query and mutate SQL-compatible service clients.",
            "service access",
            method("queryFirstCell", "write", "Return the first cell of a SQL query."),
            method("execute", "write", "Execute a SQL statement."),
            method("queryJson", "write", "Return a SQL result set as JSON.")
        ));

        groups.add(group(
            "bridge.minio",
            "Object Storage Bridge",
            "write",
            "Write text objects into an object-storage service.",
            "service access",
            method("putText", "write", "Put a text object into object storage.")
        ));

        groups.add(group(
            "bridge.player",
            "Player Bridge",
            "mixed",
            "Inspect players and perform administration actions.",
            "player permission / console",
            method("kick", "write", "Kick a player immediately or by duration."),
            method("ban", "write", "Ban a player by id."),
            method("banIP", "write", "Ban a player IP."),
            method("banID", "write", "Ban a player UUID."),
            method("unbanIP", "write", "Unban a player IP."),
            method("unbanID", "write", "Unban a player UUID."),
            method("admin", "write", "Toggle player admin status."),
            method("info", "read", "Inspect a player by id."),
            method("list", "read", "List online players."),
            method("find", "read", "Find a player by name or id."),
            method("send", "write", "Send a direct message to a player."),
            method("count", "read", "Return current player count.")
        ));

        groups.add(group(
            "bridge.world",
            "World Mutation Bridge",
            "write",
            "Place world tiles, floors and overlays in controlled batches to reduce sync churn.",
            "admin / module permission",
            method("spawn", "write", "Place one world object or tile footprint."),
            method("batchSpawn", "write", "Apply multiple placements in one call."),
            method("fill", "write", "Refill core inventories.")
        ));

        groups.add(group(
            "bridge.game",
            "Game Bridge",
            "mixed",
            "Read current game state or perform controlled world/game mutations.",
            "admin / module permission",
            method("wave", "read", "Read the current wave."),
            method("setWave", "write", "Set the current wave."),
            method("waveTime", "read", "Read the current wave timer."),
            method("setWaveTime", "write", "Set the current wave timer."),
            method("skipWave", "write", "Skip to the next wave."),
            method("tick", "read", "Read the raw game tick."),
            method("tps", "read", "Read the current TPS."),
            method("map", "read", "Read the active map metadata."),
            method("isPlaying", "read", "Check whether the game is playing."),
            method("isPaused", "read", "Check whether the game is paused."),
            method("isCampaign", "read", "Check the current mode."),
            method("isPvp", "read", "Check whether the mode is PvP."),
            method("isAttack", "read", "Check whether the mode is Attack."),
            method("enemies", "read", "Return current enemy count."),
            method("rules", "read", "Read the current game rules."),
            method("setRule", "write", "Mutate a single game rule.")
        ));

        groups.add(group(
            "bridge.net",
            "Network Bridge",
            "write",
            "Send direct or broadcast messages into the server network layer.",
            "admin / module permission",
            method("send", "write", "Send a message to a specific player."),
            method("broadcast", "write", "Broadcast a message to all players.")
        ));

        groups.add(group(
            "bridge.content",
            "Content Bridge",
            "mixed",
            "Inspect all built-in content and persist additional metadata or properties.",
            "content permission / admin",
            method("block", "read", "Inspect a block by name."),
            method("item", "read", "Inspect an item by name."),
            method("liquid", "read", "Inspect a liquid by name."),
            method("unit", "read", "Inspect a unit by name."),
            method("status", "read", "Inspect a status effect by name."),
            method("weather", "read", "Inspect a weather entry by name."),
            method("planet", "read", "Inspect a planet by name."),
            method("blocks", "read", "List all blocks."),
            method("items", "read", "List all items."),
            method("liquids", "read", "List all liquids."),
            method("units", "read", "List all units."),
            method("registerMeta", "write", "Persist custom content metadata."),
            method("getMeta", "read", "Read custom content metadata."),
            method("listMeta", "read", "List custom metadata for a namespace."),
            method("listNamespaces", "read", "List metadata namespaces."),
            method("removeMeta", "write", "Remove metadata from a namespace."),
            method("setProperty", "write", "Persist a property on a content item."),
            method("getProperty", "read", "Read a property on a content item.")
        ));

        groups.add(group(
            "bridge.ws",
            "WebSocket Bridge",
            "mixed",
            "Create and manage WebSocket connections.",
            "network access",
            method("connect", "write", "Open a new WebSocket connection."),
            method("send", "write", "Send a text payload."),
            method("sendBinary", "write", "Send a binary payload."),
            method("close", "write", "Close a WebSocket connection."),
            method("isOpen", "read", "Check whether a connection is open."),
            method("list", "read", "List active WebSocket connections.")
        ));

        groups.add(group(
            "bridge.database",
            "JSON Database Bridge",
            "mixed",
            "Manage the native local JSON database and optional remote JSON databases.",
            "module permission / admin",
            method("list", "read", "List all databases."),
            method("info", "read", "Inspect a database definition."),
            method("has", "read", "Check whether a database exists."),
            method("addLocal", "write", "Add a new local JSON database."),
            method("addRemote", "write", "Add a new remote JSON database."),
            method("remove", "write", "Remove a database definition."),
            method("categories", "read", "List database categories."),
            method("keys", "read", "List keys in a database category."),
            method("get", "read", "Read a single database entry."),
            method("set", "write", "Write a database entry."),
            method("removeEntry", "write", "Delete a database entry."),
            method("dump", "read", "Dump a database as JSON."),
            method("import", "write", "Import a database from JSON."),
            method("defaultId", "read", "Read the default database id."),
            method("count", "read", "Count configured databases.")
        ));

        groups.add(group(
            "bridge.comid",
            "COM ID Bridge",
            "mixed",
            "Map UUIDs to the persistent community ID space.",
            "account data",
            method("get", "read", "Read a COM ID by UUID."),
            method("getOrCreate", "write", "Create the COM ID if it does not exist."),
            method("uuid", "read", "Return the UUID for a COM ID."),
            method("exists", "read", "Check whether a COM ID exists."),
            method("digits", "read", "Read the current digit width."),
            method("remaining", "read", "Read how many IDs remain in the current width."),
            method("total", "read", "Read the total number of registered IDs.")
        ));

        groups.add(group(
            "bridge.data",
            "Player Data Bridge",
            "mixed",
            "Persist custom per-player data keyed by COM ID.",
            "account data",
            method("get", "read", "Read a string value."),
            method("getInt", "read", "Read an integer value."),
            method("getBool", "read", "Read a boolean value."),
            method("getDouble", "read", "Read a floating-point value."),
            method("set", "write", "Persist a string value."),
            method("setInt", "write", "Persist an integer value."),
            method("setBool", "write", "Persist a boolean value."),
            method("setDouble", "write", "Persist a floating-point value."),
            method("all", "read", "Read all stored values."),
            method("remove", "write", "Remove a stored key."),
            method("clear", "write", "Clear all stored keys.")
        ));

        groups.add(group(
            "bridge.commands",
            "Command Registry Bridge",
            "mixed",
            "Register and inspect callable commands exposed to other modules.",
            "module scope",
            method("register", "write", "Register a callable command."),
            method("unregister", "write", "Unregister a callable command."),
            method("has", "read", "Check whether a callable command exists."),
            method("call", "write", "Invoke a callable command."),
            method("run", "write", "Run a command by name with arguments."),
            method("list", "read", "List all callable commands."),
            method("listModule", "read", "List commands registered by a module.")
        ));

        groups.add(group(
            "bridge.module",
            "Module Bridge",
            "mixed",
            "Export functions from a module and inspect modules loaded at runtime.",
            "module scope",
            method("list", "read", "List discovered modules."),
            method("info", "read", "Inspect one module."),
            method("export", "write", "Export a function from the module scope."),
            method("call", "write", "Call another module's exported function."),
            method("exported", "read", "List exported functions from another module.")
        ));

        groups.add(group(
            "bridge.mod",
            "Mod Command Bridge",
            "mixed",
            "Register server/player/callable commands for mods with optional permissions.",
            "admin / module scope",
            method("registerServerCommand", "write", "Register a console command."),
            method("registerPlayerCommand", "write", "Register a player command."),
            method("registerAdminCommand", "write", "Register an admin-only player command."),
            method("registerCallableCommand", "write", "Register a callable command."),
            method("unregisterCommand", "write", "Unregister a mod command."),
            method("listCommands", "read", "List all mod commands."),
            method("hasCommand", "read", "Check whether a mod command exists.")
        ));

        return groups;
    }

    private static CapabilityGroup group(String id, String title, String access, String summary, String permission, MethodSpec... methods){
        CapabilityGroup group = new CapabilityGroup();
        group.id = id;
        group.title = title;
        group.access = access;
        group.summary = summary;
        group.permission = permission;
        group.risk = "write".equals(access) ? "high" : ("mixed".equals(access) ? "medium" : "low");
        group.sideEffects = !"read".equals(access);
        for(MethodSpec method : methods){
            group.methods.add(method);
        }
        return group;
    }

    private static MethodSpec method(String name, String access, String description){
        MethodSpec method = new MethodSpec();
        method.name = name;
        method.access = access;
        method.description = description;
        method.risk = "write".equals(access) ? "high" : "low";
        method.sideEffects = "write".equals(access);
        return method;
    }

    private static final class CapabilityGroup{
        String id;
        String title;
        String access;
        String summary;
        String permission;
        String risk;
        boolean sideEffects;
        final Seq<MethodSpec> methods = new Seq<>();
    }

    private static final class MethodSpec{
        String name;
        String access;
        String description;
        String risk;
        boolean sideEffects;
    }
}
