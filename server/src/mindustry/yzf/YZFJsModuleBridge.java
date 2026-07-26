package mindustry.yzf;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import rhino.Function;
import rhino.Scriptable;

import static mindustry.Vars.*;

public final class YZFJsModuleBridge{
    private final YZFJsRuntime runtime;
    private final YZFModuleDefinition module;
    private final Scriptable scope;
    private final Seq<String> commandNames = new Seq<>();
    private final Seq<YZFPlayerCommandBinding> playerCommandNames = new Seq<>();
    private final Seq<YZFEventBinding> eventBindings = new Seq<>();
    private final Seq<YZFTaskBinding> taskBindings = new Seq<>();
    private final YZFModuleConfigStore configStore;
    private final YZFScriptServices services;
    private Function onEnable;
    private Function onDisable;

    public YZFJsModuleBridge(YZFJsRuntime runtime, YZFModuleDefinition module, Scriptable scope){
        this.runtime = runtime;
        this.module = module;
        this.scope = scope;
        this.configStore = new YZFModuleConfigStore(module);
        this.services = new YZFScriptServices(MindustryYZF.context());
    }

    public void command3(String name, String description, Object callback){
        command4(name, "", description, callback);
    }

    public void command4(String name, String usage, String description, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.command 的最后一个参数必须是函数。");
        }
        String commandName = normalize(name);
        if(!YZFSecurity.validCommandName(commandName)){
            throw new IllegalArgumentException("非法命令名: " + commandName);
        }
        runtime.registerModuleCommand(module, commandName, usage == null ? "" : usage, description == null ? "" : description, (Function)callback);
        if(!commandNames.contains(commandName)){
            commandNames.add(commandName);
        }
    }

    public void playerCommand4(String name, String usage, String description, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.playerCommand 的最后一个参数必须是函数。");
        }
        String commandName = normalize(name);
        if(!YZFSecurity.validCommandName(commandName)){
            throw new IllegalArgumentException("非法玩家命令名: " + commandName);
        }
        runtime.registerPlayerCommand(module, commandName, usage == null ? "" : usage, description == null ? "" : description, false, module.meta.permission, (Function)callback);
        playerCommandNames.add(new YZFPlayerCommandBinding(commandName, false, module.meta.permission));
    }

    public void adminCommand4(String name, String usage, String description, String permission, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.adminCommand 的最后一个参数必须是函数。");
        }
        String commandName = normalize(name);
        if(!YZFSecurity.validCommandName(commandName)){
            throw new IllegalArgumentException("非法管理命令名: " + commandName);
        }
        String finalPermission = YZFText.blank(permission) ? module.meta.permission : permission;
        runtime.registerPlayerCommand(module, commandName, usage == null ? "" : usage, description == null ? "" : description, true, finalPermission, (Function)callback);
        playerCommandNames.add(new YZFPlayerCommandBinding(commandName, true, finalPermission));
    }

    public void onEnable(Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.onEnable 参数必须是函数。");
        }
        onEnable = (Function)callback;
    }

    public void onDisable(Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.onDisable 参数必须是函数。");
        }
        onDisable = (Function)callback;
    }

    public void uiRegisterPage(String pageId, String descriptorJson){
        MindustryYZF.context().webUi.register(module.fullId(), pageId, descriptorJson);
    }

    public boolean uiUnregisterPage(String pageId){
        return MindustryYZF.context().webUi.unregister(module.fullId(), pageId);
    }

    public void onEvent(String eventName, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.on 参数必须是函数。");
        }
        YZFEventBinding binding = runtime.registerModuleEvent(module, scope, eventName, (Function)callback);
        eventBindings.add(binding);
    }

    public void after(float delaySeconds, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.after 参数必须是函数。");
        }
        String id = "once-" + taskBindings.size;
        Timer.Task task = runtime.scheduleOnce(scope, delaySeconds, (Function)callback);
        taskBindings.add(new YZFTaskBinding(id, "once", task));
    }

    public void every(float delaySeconds, float intervalSeconds, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.every 参数必须是函数。");
        }
        String id = "repeat-" + taskBindings.size;
        Timer.Task task = runtime.scheduleRepeating(scope, delaySeconds, intervalSeconds, (Function)callback);
        taskBindings.add(new YZFTaskBinding(id, "repeat", task));
    }

    public String configGet(String key, String defaultValue){
        return configStore.getString(key, defaultValue);
    }

    public boolean configGetBool(String key, boolean defaultValue){
        return configStore.getBool(key, defaultValue);
    }

    public int configGetInt(String key, int defaultValue){
        return configStore.getInt(key, defaultValue);
    }

    public void configSet(String key, String value){
        configStore.putString(key, value);
    }

    public void configSetBool(String key, boolean value){
        configStore.putBool(key, value);
    }

    public void configSetInt(String key, int value){
        configStore.putInt(key, value);
    }

    public String configPath(){
        return configStore.path();
    }

    public String httpGet(String serviceId, String path) throws Exception{
        return services.httpGet(serviceId, path);
    }

    public String httpPostJson(String serviceId, String path, String body) throws Exception{
        return services.httpPostJson(serviceId, path, body);
    }

    public void redisSet(String serviceId, String key, String value){
        services.redisSet(serviceId, key, value);
    }

    public String redisGet(String serviceId, String key){
        return services.redisGet(serviceId, key);
    }

    public long redisIncrement(String serviceId, String key){
        return services.redisIncrement(serviceId, key);
    }

    public void redisDelete(String serviceId, String key){
        services.redisDelete(serviceId, key);
    }

    public void redisHashSet(String serviceId, String key, String field, String value){
        services.redisHashSet(serviceId, key, field, value);
    }

    public String redisHashGet(String serviceId, String key, String field){
        return services.redisHashGet(serviceId, key, field);
    }

    public String sqlQueryFirstCell(String serviceId, String sql) throws Exception{
        return services.sqlQueryFirstCell(serviceId, sql);
    }

    public int sqlExecute(String serviceId, String sql) throws Exception{
        return services.sqlExecute(serviceId, sql);
    }

    public String sqlQueryJson(String serviceId, String sql) throws Exception{
        return services.sqlQueryJson(serviceId, sql);
    }

    public void minioPutText(String serviceId, String objectName, String text) throws Exception{
        services.minioPutText(serviceId, objectName, text);
    }

    public boolean hasService(String serviceId){
        return MindustryYZF.context().services.registry().get(serviceId) != null;
    }

    public String serviceSummary(String serviceId){
        YZFServiceClient client = MindustryYZF.context().services.registry().get(serviceId);
        return client == null ? null : client.summary();
    }

    public String runtimeMode(){
        return runtime.mode();
    }

    public String runtimeConfig(){
        return mapJson(MindustryYZF.context().runtimeConfig.snapshot());
    }

    public String runtimeModules(){
        return runtime.runtimeModulesJson();
    }

    public boolean runtimeTerminate(String moduleId){
        return runtime.terminateModule(moduleId);
    }

    public boolean runtimeSetMemory(String moduleId, String minHeap, String maxHeap){
        return runtime.setModuleMemoryLimits(moduleId, minHeap, maxHeap);
    }

    public boolean watcherRunning(){
        return MindustryYZF.context().watcher.running();
    }

    public void requestReloadSelf(){
        runtime.requestReloadModule(module.fullId());
    }

    public void requestReloadModule(String moduleId){
        runtime.requestReloadModule(moduleId);
    }

    public void requestReloadAll(){
        runtime.requestReloadAll();
    }

    public String memoryJvm(){
        return mapJson(MindustryYZF.context().memoryRegions.jvmSnapshot());
    }

    public String memoryList(){
        Jval array = Jval.newArray();
        for(java.util.Map<String, Object> item : MindustryYZF.context().memoryRegions.list()) array.add(mapJson(item));
        return array.toString(Jval.Jformat.plain);
    }

    public String memoryInfo(String id){
        YZFMemoryRegion region = MindustryYZF.context().memoryRegions.get(id);
        return region == null ? null : mapJson(region.snapshot());
    }

    public String memoryCreate(String id, String mode, String minHeap, String maxHeap){
        if(!MindustryYZF.context().runtimeConfig.allowPluginCreateRegion) throw new IllegalStateException("plugin region creation is disabled");
        YZFMemoryRegion region = MindustryYZF.context().memoryRegions.create(id, mode, YZFMemoryRegionManager.parseBytes(minHeap), YZFMemoryRegionManager.parseBytes(maxHeap));
        return mapJson(region.snapshot());
    }

    public boolean memoryStop(String id){
        return MindustryYZF.context().memoryRegions.stop(id);
    }

    public String memoryLoad(String regionId, String jarPath, String className) throws Exception{
        return MindustryYZF.context().memoryRegions.loadClassLoaderJar(regionId, jarPath, className);
    }

    private String mapJson(java.util.Map<String, ?> map){
        Jval root = Jval.newObject();
        for(java.util.Map.Entry<String, ?> entry : map.entrySet()){
            Object value = entry.getValue();
            if(value instanceof Iterable<?> iterable){
                Jval array = Jval.newArray();
                for(Object item : iterable) array.add(item == null ? null : String.valueOf(item));
                root.put(entry.getKey(), array);
            }else if(value instanceof Number number) root.put(entry.getKey(), number);
            else if(value instanceof Boolean bool) root.put(entry.getKey(), bool);
            else root.put(entry.getKey(), value == null ? null : String.valueOf(value));
        }
        return root.toString(Jval.Jformat.plain);
    }

    public String serviceCall2(String serviceId, String action) throws Exception{
        return services.serviceCall(serviceId, action);
    }

    public String serviceCall3(String serviceId, String action, String arg0) throws Exception{
        return services.serviceCall(serviceId, action, arg0);
    }

    public String serviceCall4(String serviceId, String action, String arg0, String arg1) throws Exception{
        return services.serviceCall(serviceId, action, arg0, arg1);
    }

    public String serviceCall5(String serviceId, String action, String arg0, String arg1, String arg2) throws Exception{
        return services.serviceCall(serviceId, action, arg0, arg1, arg2);
    }

    public String serviceList(){
        Jval array = Jval.newArray();
        for(String id : MindustryYZF.context().services.registry().ids()){
            array.add(id);
        }
        return array.toString(Jval.Jformat.plain);
    }

    public String serviceInfo(String serviceId){
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

    public String openApiManifest(){
        return YZFOpenApiRegistry.manifestJson();
    }

    public String openApiList(){
        return YZFOpenApiRegistry.listJson();
    }

    public String openApiInfo(String capabilityId){
        return YZFOpenApiRegistry.infoJson(capabilityId);
    }

    public String openApiSummary(){
        return YZFOpenApiRegistry.summaryJson();
    }

    public String openApiReadOnly(){
        return YZFOpenApiRegistry.readOnlyJson();
    }

    public String openApiWriteOnly(){
        return YZFOpenApiRegistry.writeOnlyJson();
    }

    /** Calls a configuration-backed stable API by its public id. */
    public Object stableApi(String id){
        return YZFStableApi.call(id);
    }

    public String stableApiManifest(){
        return YZFStableApi.manifestJson();
    }

    public String statusSnapshot(){
        return YZFStatusUi.statusJson();
    }

    public String uhdStatusUi(){
        return YZFStatusUi.uhdStatusUiJson();
    }

    public String moduleList(){
        Jval array = Jval.newArray();
        for(YZFModuleDefinition definition : MindustryYZF.context().registry.modules()){
            array.add(definition.fullId());
        }
        return array.toString(Jval.Jformat.plain);
    }

    public String moduleInfo(String moduleId){
        YZFModuleDefinition definition = MindustryYZF.context().registry.find(moduleId);
        if(definition == null) return null;

        Jval root = Jval.newObject();
        root.put("id", definition.id());
        root.put("fullId", definition.fullId());
        root.put("runtime", definition.meta.runtime);
        root.put("enabled", definition.meta.enabled);
        root.put("category", definition.meta.category);
        root.put("permission", definition.meta.permission);
        root.put("main", definition.meta.main);
        root.put("root", definition.root.absolutePath());
        return root.toString(Jval.Jformat.plain);
    }

    public void log(String message){
        Log.info("[@] [@] @", MindustryYZF.name, module.fullId(), message);
    }

    public void info(String message){
        Log.info("[@] [@] @", MindustryYZF.name, module.fullId(), message);
    }

    public void warn(String message){
        Log.warn("[@] [@] @", MindustryYZF.name, module.fullId(), message);
    }

    public void err(String message){
        Log.err("[@] [@] @", MindustryYZF.name, module.fullId(), message);
    }

    // ========== yzf.player.* — 玩家管理 ==========

    public boolean playerKick(int playerId, String reason){
        Player player = Groups.player.getByID(playerId);
        if(player == null) return false;
        player.kick(reason == null ? "" : reason);
        return true;
    }

    public boolean playerKickDuration(int playerId, String reason, long durationMs){
        Player player = Groups.player.getByID(playerId);
        if(player == null) return false;
        player.kick(reason == null ? "" : reason, durationMs);
        return true;
    }

    public boolean playerBan(int playerId){
        Player player = Groups.player.getByID(playerId);
        if(player == null) return false;
        return netServer.admins.banPlayer(player.uuid());
    }

    public boolean playerBanIP(String ip){
        return netServer.admins.banPlayerIP(ip);
    }

    public boolean playerBanID(String uuidOrComid){
        String uuid = resolveUuid(uuidOrComid);
        return uuid != null && netServer.admins.banPlayerID(uuid);
    }

    public boolean playerUnbanIP(String ip){
        return netServer.admins.unbanPlayerIP(ip);
    }

    public boolean playerUnbanID(String uuidOrComid){
        String uuid = resolveUuid(uuidOrComid);
        return uuid != null && netServer.admins.unbanPlayerID(uuid);
    }

    public boolean playerAdmin(int playerId, boolean admin){
        Player player = Groups.player.getByID(playerId);
        if(player == null) return false;
        if(admin){
            return netServer.admins.adminPlayer(player.uuid(), player.usid());
        }else{
            return netServer.admins.unAdminPlayer(player.uuid());
        }
    }

    public boolean playerAdminComid(long comid, boolean admin){
        Player player = playerByComid(comid);
        if(player == null) return false;
        return playerAdmin(player.id, admin);
    }

    public String playerInfo(int playerId){
        Player player = Groups.player.getByID(playerId);
        if(player == null) return null;
        return playerToJson(player).toString(Jval.Jformat.plain);
    }

    public String playerInfoByComid(long comid){
        Player player = playerByComid(comid);
        if(player == null) return null;
        return playerToJson(player).toString(Jval.Jformat.plain);
    }

    public String playerList(){
        Jval array = Jval.newArray();
        for(Player player : Groups.player){
            array.add(playerToJson(player));
        }
        return array.toString(Jval.Jformat.plain);
    }

    public String playerFind(String nameOrId){
        if(nameOrId == null) return null;
        Player found = null;
        try{
            long comid = Long.parseLong(nameOrId);
            found = playerByComid(comid);
        }catch(NumberFormatException e){
            try{
                int id = Integer.parseInt(nameOrId);
                found = Groups.player.getByID(id);
            }catch(NumberFormatException ignored){}
            // search by name
            if(found == null){
                String lower = nameOrId.toLowerCase();
                for(Player p : Groups.player){
                    if(p.plainName().toLowerCase().contains(lower)){
                        found = p;
                        break;
                    }
                }
            }
        }
        return found == null ? null : playerToJson(found).toString(Jval.Jformat.plain);
    }

    public void playerSend(int playerId, String message){
        Player player = Groups.player.getByID(playerId);
        if(player != null){
            player.sendMessage(message);
        }
    }

    public int playerCount(){
        return Groups.player.size();
    }

    private Jval playerToJson(Player player){
        Jval obj = Jval.newObject();
        obj.put("id", player.id);
        obj.put("name", player.plainName());
        obj.put("uuid", player.uuid() == null ? "" : player.uuid());
        obj.put("ip", player.ip() == null ? "" : player.ip());
        obj.put("admin", player.admin);
        obj.put("team", player.team().id);
        obj.put("x", (double)player.x);
        obj.put("y", (double)player.y);
        obj.put("dead", player.dead());
        if(player.uuid() != null){
            long comid = MindustryYZF.context().comidRegistry.getOrCreate(player.uuid());
            if(comid >= 0) obj.put("comid", comid);
        }
        return obj;
    }

    private Player playerByComid(long comid){
        String uuid = MindustryYZF.context().comidRegistry.getUuid(comid);
        if(uuid == null || uuid.isBlank()) return null;
        for(Player player : Groups.player){
            if(uuid.equals(player.uuid())) return player;
        }
        return null;
    }

    private String resolveUuid(String uuidOrComid){
        if(uuidOrComid == null) return null;
        String trimmed = uuidOrComid.trim();
        if(trimmed.isEmpty()) return null;
        try{
            long comid = Long.parseLong(trimmed);
            String uuid = MindustryYZF.context().comidRegistry.getUuid(comid);
            if(uuid != null && !uuid.isBlank()) return uuid;
        }catch(NumberFormatException ignored){
        }
        return trimmed;
    }

    // ========== yzf.game.* — 游戏状态 ==========

    public int gameWave(){
        return state.wave;
    }

    public void gameSetWave(int wave){
        state.wave = wave;
    }

    public float gameWaveTime(){
        return state.wavetime;
    }

    public void gameSetWaveTime(float ticks){
        state.wavetime = ticks;
    }

    public void gameSkipWave(){
        logic.skipWave();
    }

    public double gameTick(){
        return state.tick;
    }

    public int gameTps(){
        return mindustry.server.ServerLauncher.actualTps();
    }

    public String gameMap(){
        Jval obj = Jval.newObject();
        obj.put("name", state.map.name());
        obj.put("width", world.width());
        obj.put("height", world.height());
        return obj.toString(Jval.Jformat.plain);
    }

    public boolean gameIsPlaying(){
        return state.isPlaying();
    }

    public boolean gameIsPaused(){
        return state.isPaused();
    }

    public boolean gameIsCampaign(){
        return state.isCampaign();
    }

    public boolean gameIsPvp(){
        return state.rules.pvp;
    }

    public boolean gameIsAttack(){
        return state.rules.attackMode;
    }

    public int gameEnemies(){
        return state.enemies;
    }

    public String gameRules(){
        Rules r = state.rules;
        Jval obj = Jval.newObject();
        obj.put("waves", r.waves);
        obj.put("waveTimer", r.waveTimer);
        obj.put("waveSpacing", (double)r.waveSpacing);
        obj.put("pvp", r.pvp);
        obj.put("attackMode", r.attackMode);
        obj.put("infiniteResources", r.infiniteResources);
        obj.put("fog", r.fog);
        obj.put("lighting", r.lighting);
        obj.put("unitCap", r.unitCap);
        obj.put("unitBuildSpeedMultiplier", (double)r.unitBuildSpeedMultiplier);
        obj.put("unitDamageMultiplier", (double)r.unitDamageMultiplier);
        obj.put("unitHealthMultiplier", (double)r.unitHealthMultiplier);
        obj.put("blockHealthMultiplier", (double)r.blockHealthMultiplier);
        obj.put("blockDamageMultiplier", (double)r.blockDamageMultiplier);
        obj.put("buildSpeedMultiplier", (double)r.buildSpeedMultiplier);
        obj.put("buildCostMultiplier", (double)r.buildCostMultiplier);
        obj.put("defaultTeam", r.defaultTeam.id);
        obj.put("waveTeam", r.waveTeam.id);
        obj.put("winWave", r.winWave);
        return obj.toString(Jval.Jformat.plain);
    }

    public boolean gameSetRule(String key, String value){
        if(key == null || value == null) return false;
        Rules r = state.rules;
        switch(key){
            case "waves" -> r.waves = Boolean.parseBoolean(value);
            case "waveTimer" -> r.waveTimer = Boolean.parseBoolean(value);
            case "waveSpacing" -> r.waveSpacing = Float.parseFloat(value);
            case "pvp" -> r.pvp = Boolean.parseBoolean(value);
            case "attackMode" -> r.attackMode = Boolean.parseBoolean(value);
            case "infiniteResources" -> r.infiniteResources = Boolean.parseBoolean(value);
            case "fog" -> r.fog = Boolean.parseBoolean(value);
            case "lighting" -> r.lighting = Boolean.parseBoolean(value);
            case "unitCap" -> r.unitCap = Integer.parseInt(value);
            case "unitBuildSpeedMultiplier" -> r.unitBuildSpeedMultiplier = Float.parseFloat(value);
            case "unitDamageMultiplier" -> r.unitDamageMultiplier = Float.parseFloat(value);
            case "unitHealthMultiplier" -> r.unitHealthMultiplier = Float.parseFloat(value);
            case "blockHealthMultiplier" -> r.blockHealthMultiplier = Float.parseFloat(value);
            case "blockDamageMultiplier" -> r.blockDamageMultiplier = Float.parseFloat(value);
            case "buildSpeedMultiplier" -> r.buildSpeedMultiplier = Float.parseFloat(value);
            case "buildCostMultiplier" -> r.buildCostMultiplier = Float.parseFloat(value);
            case "winWave" -> r.winWave = Integer.parseInt(value);
            case "defaultTeam" -> r.defaultTeam = Team.get(Integer.parseInt(value));
            case "waveTeam" -> r.waveTeam = Team.get(Integer.parseInt(value));
            default -> { return false; }
        }
        return true;
    }

    // ========== yzf.net.* — 网络消息 ==========

    public void netSend(int playerId, String message){
        Player player = Groups.player.getByID(playerId);
        if(player != null){
            player.sendMessage(message);
        }
    }

    public void netBroadcast(String message){
        Call.sendMessage(message);
    }

    public void netBroadcastFrom(String message, int senderId){
        Player sender = Groups.player.getByID(senderId);
        if(sender != null){
            Call.sendMessage(message, null, sender);
        }else{
            Call.sendMessage(message);
        }
    }

    // ========== yzf.content.* — 内容查找 ==========

    public String contentBlock(String name){
        return contentToJson(Vars.content.block(name), ContentType.block);
    }

    public String contentItem(String name){
        return contentToJson(Vars.content.item(name), ContentType.item);
    }

    public String contentLiquid(String name){
        return contentToJson(Vars.content.liquid(name), ContentType.liquid);
    }

    public String contentUnit(String name){
        return contentToJson(Vars.content.unit(name), ContentType.unit);
    }

    public String contentStatus(String name){
        return contentToJson(Vars.content.statusEffect(name), ContentType.status);
    }

    public String contentWeather(String name){
        return contentToJson(Vars.content.weather(name), ContentType.weather);
    }

    public String contentPlanet(String name){
        return contentToJson(Vars.content.planet(name), ContentType.planet);
    }

    public String contentBlocks(){
        return contentListJson(Vars.content.blocks());
    }

    public String contentItems(){
        return contentListJson(Vars.content.items());
    }

    public String contentLiquids(){
        return contentListJson(Vars.content.liquids());
    }

    public String contentUnits(){
        return contentListJson(Vars.content.units());
    }

    @SuppressWarnings("unchecked")
    private String contentToJson(Content content, ContentType type){
        if(content == null) return null;
        Jval obj = Jval.newObject();
        obj.put("name", content instanceof MappableContent mc ? mc.name : content.toString());
        obj.put("id", content.id);
        obj.put("type", type.name());
        return obj.toString(Jval.Jformat.plain);
    }

    @SuppressWarnings("unchecked")
    private <T extends Content> String contentListJson(Seq<T> list){
        Jval array = Jval.newArray();
        for(Content c : list){
            Jval obj = Jval.newObject();
            obj.put("name", c instanceof MappableContent mc ? mc.name : c.toString());
            obj.put("id", c.id);
            array.add(obj);
        }
        return array.toString(Jval.Jformat.plain);
    }

    // ========== yzf.world.* — 世界操作 ==========

    /**
     * 在指定位置生成内容。
     * @param type 类型: "unit", "block", "floor", "liquid", "overlay"
     * @param id   内容名称 (如 "flare", "copper-wall", "water")
     * @param x    X坐标（方块/地板/液体为tile坐标，单位为世界坐标）
     * @param y    Y坐标
     * @param size 大小: 1=1x1, 2=3x3, 3=5x5（向外扩展）。默认1
     * @param teamId 队伍ID。默认1 (sharded/黄队)
     * @param buff 状态效果名称（仅对unit有效）。可为null
     * @return JSON 结果 {success, message, spawned}
     */
    public String worldSpawn(String type, String id, int x, int y, int size, int teamId, String buff){
        Jval result = Jval.newObject();
        if(type == null || id == null){
            result.put("success", false);
            result.put("message", "type 和 id 不能为空");
            return result.toString(Jval.Jformat.plain);
        }

        Team team = Team.get(teamId);
        if(size < 1) size = 1;

        try{
            switch(type.toLowerCase()){
                case "unit" -> {
                    UnitType unitType = content.unit(id);
                    if(unitType == null){
                        result.put("success", false);
                        result.put("message", "找不到单位: " + id);
                        return result.toString(Jval.Jformat.plain);
                    }
                    int spawned = 0;
                    int half = size - 1; // size=1 -> half=0, size=2 -> half=1 (3x3)
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Unit unit = unitType.spawn(team, x + dx * tilesize, y + dy * tilesize);
                            if(buff != null && !buff.isEmpty()){
                                for(String buffName : buff.split(",")){
                                    String trimmedBuff = buffName.trim();
                                    if(trimmedBuff.isEmpty()) continue;
                                    StatusEffect effect = content.statusEffect(trimmedBuff);
                                    if(effect != null) unit.apply(effect);
                                }
                            }
                            spawned++;
                        }
                    }
                    result.put("success", true);
                    result.put("spawned", spawned);
                    result.put("message", "生成了 " + spawned + " 个 " + unitType.name);
                }
                case "block" -> {
                    Block block = content.block(id);
                    if(block == null){
                        result.put("success", false);
                        result.put("message", "找不到方块: " + id);
                        return result.toString(Jval.Jformat.plain);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setBlock(block, team, 0);
                                placed++;
                            }
                        }
                    }
                    result.put("success", true);
                    result.put("spawned", placed);
                    result.put("message", "放置了 " + placed + " 个 " + block.name);
                }
                case "floor" -> {
                    Block floor = content.block(id);
                    if(floor == null || !(floor instanceof Floor)){
                        result.put("success", false);
                        result.put("message", "找不到地板: " + id);
                        return result.toString(Jval.Jformat.plain);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setFloor((Floor)floor);
                                placed++;
                            }
                        }
                    }
                    result.put("success", true);
                    result.put("spawned", placed);
                    result.put("message", "设置了 " + placed + " 个地板 " + floor.name);
                }
                case "overlay" -> {
                    Block overlay = content.block(id);
                    if(overlay == null){
                        result.put("success", false);
                        result.put("message", "找不到overlay: " + id);
                        return result.toString(Jval.Jformat.plain);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setOverlay(overlay);
                                placed++;
                            }
                        }
                    }
                    result.put("success", true);
                    result.put("spawned", placed);
                    result.put("message", "设置了 " + placed + " 个overlay " + overlay.name);
                }
                case "liquid" -> {
                    Liquid liquid = content.liquid(id);
                    if(liquid == null){
                        result.put("success", false);
                        result.put("message", "找不到液体: " + id);
                        return result.toString(Jval.Jformat.plain);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null && tile.build != null && tile.build.block.hasLiquids){
                                tile.build.liquids.add(liquid, tile.build.block.liquidCapacity);
                                placed++;
                            }
                        }
                    }
                    result.put("success", true);
                    result.put("spawned", placed);
                    if(placed == 0){
                        result.put("message", "没有找到可接受液体的建筑（需要有液体模块的建筑）");
                    }else{
                        result.put("message", "向 " + placed + " 个建筑注入了液体 " + liquid.name);
                    }
                }
                default -> {
                    result.put("success", false);
                    result.put("message", "未知类型: " + type + "。支持: unit, block, floor, liquid, overlay");
                }
            }
        }catch(Exception e){
            result.put("success", false);
            result.put("message", "错误: " + e.getMessage());
        }
        return result.toString(Jval.Jformat.plain);
    }

    /**
     * 填充核心物品。
     * @param itemId 物品名称或 "ALL" 表示全部物品
     * @param amount 数量
     * @param teamId 队伍ID。默认1 (sharded/黄队)
     * @return JSON 结果 {success, message, filled}
     */
    public String worldFill(String itemId, int amount, int teamId){
        Jval result = Jval.newObject();
        Team team = Team.get(teamId);
        Seq<CoreBuild> cores = team.cores();

        if(cores.isEmpty()){
            result.put("success", false);
            result.put("message", "队伍 " + teamId + " 没有核心");
            return result.toString(Jval.Jformat.plain);
        }

        try{
            if("all".equalsIgnoreCase(itemId)){
                int totalItems = 0;
                for(Item item : content.items()){
                    for(CoreBuild core : cores){
                        core.items.add(item, amount);
                    }
                    totalItems++;
                }
                result.put("success", true);
                result.put("filled", totalItems);
                result.put("message", "向 " + cores.size + " 个核心填充了全部 " + totalItems + " 种物品各 " + amount + " 个");
            }else{
                Item item = content.item(itemId);
                if(item == null){
                    result.put("success", false);
                    result.put("message", "找不到物品: " + itemId);
                    return result.toString(Jval.Jformat.plain);
                }
                for(CoreBuild core : cores){
                    core.items.add(item, amount);
                }
                result.put("success", true);
                result.put("filled", 1);
                result.put("message", "向 " + cores.size + " 个核心填充了 " + amount + " 个 " + item.name);
            }
        }catch(Exception e){
            result.put("success", false);
            result.put("message", "错误: " + e.getMessage());
        }
        return result.toString(Jval.Jformat.plain);
    }

    public String worldBatchSpawn(String operationsJson){
        if(YZFText.blank(operationsJson)){
            return YZFResponse.fail("world.batch.empty", "批量操作内容为空").toString(Jval.Jformat.plain);
        }

        try{
            Jval parsed = Jval.read(operationsJson);
            Seq<Jval> operations = new Seq<>();
            if(parsed.isArray()){
                for(Jval child : parsed.asArray()){
                    operations.add(child);
                }
            }else if(parsed.isObject() && parsed.has("operations") && parsed.get("operations").isArray()){
                for(Jval child : parsed.get("operations").asArray()){
                    operations.add(child);
                }
            }else{
                return YZFResponse.fail("world.batch.invalid", "批量操作必须是数组或包含 operations 字段的对象").toString(Jval.Jformat.plain);
            }

            Jval results = Jval.newArray();
            int successCount = 0;
            int failCount = 0;
            int spawnedTotal = 0;

            for(Jval op : operations){
                if(op == null || !op.isObject()){
                    results.add(YZFResponse.fail("world.batch.item.invalid", "单个操作必须是对象"));
                    failCount++;
                    continue;
                }

                String buff = null;
                if(op.has("buff")){
                    Jval buffValue = op.get("buff");
                    if(buffValue != null){
                        if(buffValue.isString()){
                            buff = buffValue.asString();
                        }else if(buffValue.isArray() && buffValue.asArray().size > 0){
                            StringBuilder buffJoin = new StringBuilder();
                            for(Jval child : buffValue.asArray()){
                                if(child != null && child.isString() && !YZFText.blank(child.asString())){
                                    if(buffJoin.length() > 0) buffJoin.append(',');
                                    buffJoin.append(child.asString().trim());
                                }
                            }
                            buff = buffJoin.length() == 0 ? null : buffJoin.toString();
                        }
                    }
                    if(YZFText.blank(buff)) buff = null;
                }
                Jval result = worldSpawnItem(
                    op.getString("spawnType", op.getString("type", null)),
                    op.getString("id", null),
                    op.getInt("x", 0),
                    op.getInt("y", 0),
                    op.getInt("size", 1),
                    op.getInt("teamId", 1),
                    buff
                );
                results.add(result);
                if(result.getBool("ok", false)){
                    successCount++;
                    spawnedTotal += result.getInt("spawned", 0);
                }else{
                    failCount++;
                }
            }

            Jval data = Jval.newObject();
            data.put("operationCount", operations.size);
            data.put("successCount", successCount);
            data.put("failCount", failCount);
            data.put("spawnedTotal", spawnedTotal);
            data.put("results", results);

            Jval root = YZFResponse.build(failCount == 0, failCount == 0 ? "world.batch.ok" : "world.batch.partial", failCount == 0 ? "批量世界操作完成" : "批量世界操作完成，但有部分失败", data);
            root.put("successCount", successCount);
            root.put("failCount", failCount);
            root.put("spawnedTotal", spawnedTotal);
            return root.toString(Jval.Jformat.plain);
        }catch(Exception e){
            return YZFResponse.fail("world.batch.error", "批量世界操作失败: " + e.getMessage()).toString(Jval.Jformat.plain);
        }
    }

    private Jval worldSpawnItem(String type, String id, int x, int y, int size, int teamId, String buff){
        if(type == null || id == null){
            return YZFResponse.fail("world.spawn.invalid", "type 和 id 不能为空");
        }

        Team team = Team.get(teamId);
        if(size < 1) size = 1;

        try{
            switch(type.toLowerCase()){
                case "unit" -> {
                    UnitType unitType = content.unit(id);
                    if(unitType == null){
                        return YZFResponse.fail("world.spawn.unit_missing", "找不到单位: " + id);
                    }
                    int spawned = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Unit unit = unitType.spawn(team, x + dx * tilesize, y + dy * tilesize);
                            if(buff != null && !buff.isEmpty()){
                                for(String buffName : buff.split(",")){
                                    String trimmedBuff = buffName.trim();
                                    if(trimmedBuff.isEmpty()) continue;
                                    StatusEffect effect = content.statusEffect(trimmedBuff);
                                    if(effect != null) unit.apply(effect);
                                }
                            }
                            spawned++;
                        }
                    }
                    Jval data = Jval.newObject();
                    data.put("type", "unit");
                    data.put("id", id);
                    data.put("spawned", spawned);
                    data.put("x", x);
                    data.put("y", y);
                    data.put("size", size);
                    data.put("teamId", teamId);
                    if(buff != null) data.put("buff", buff);
                    return YZFResponse.ok("world.spawn.unit_ok", "已生成 " + spawned + " 个 " + unitType.name, data);
                }
                case "block" -> {
                    Block block = content.block(id);
                    if(block == null){
                        return YZFResponse.fail("world.spawn.block_missing", "找不到方块: " + id);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setBlock(block, team, 0);
                                placed++;
                            }
                        }
                    }
                    Jval data = Jval.newObject();
                    data.put("type", "block");
                    data.put("id", id);
                    data.put("spawned", placed);
                    data.put("x", x);
                    data.put("y", y);
                    data.put("size", size);
                    data.put("teamId", teamId);
                    return YZFResponse.ok("world.spawn.block_ok", "已放置 " + placed + " 个 " + block.name, data);
                }
                case "floor" -> {
                    Block floor = content.block(id);
                    if(floor == null || !(floor instanceof Floor)){
                        return YZFResponse.fail("world.spawn.floor_missing", "找不到地板: " + id);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setFloor((Floor)floor);
                                placed++;
                            }
                        }
                    }
                    Jval data = Jval.newObject();
                    data.put("type", "floor");
                    data.put("id", id);
                    data.put("spawned", placed);
                    data.put("x", x);
                    data.put("y", y);
                    data.put("size", size);
                    data.put("teamId", teamId);
                    return YZFResponse.ok("world.spawn.floor_ok", "已设置 " + placed + " 个地板 " + floor.name, data);
                }
                case "overlay" -> {
                    Block overlay = content.block(id);
                    if(overlay == null){
                        return YZFResponse.fail("world.spawn.overlay_missing", "找不到 overlay: " + id);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null){
                                tile.setOverlay(overlay);
                                placed++;
                            }
                        }
                    }
                    Jval data = Jval.newObject();
                    data.put("type", "overlay");
                    data.put("id", id);
                    data.put("spawned", placed);
                    data.put("x", x);
                    data.put("y", y);
                    data.put("size", size);
                    data.put("teamId", teamId);
                    return YZFResponse.ok("world.spawn.overlay_ok", "已设置 " + placed + " 个 overlay " + overlay.name, data);
                }
                case "liquid" -> {
                    Liquid liquid = content.liquid(id);
                    if(liquid == null){
                        return YZFResponse.fail("world.spawn.liquid_missing", "找不到液体: " + id);
                    }
                    int placed = 0;
                    int half = size - 1;
                    for(int dx = -half; dx <= half; dx++){
                        for(int dy = -half; dy <= half; dy++){
                            Tile tile = world.tile(x + dx, y + dy);
                            if(tile != null && tile.build != null && tile.build.block.hasLiquids){
                                tile.build.liquids.add(liquid, tile.build.block.liquidCapacity);
                                placed++;
                            }
                        }
                    }
                    if(placed == 0){
                        return YZFResponse.fail("world.spawn.liquid_no_target", "没有找到可接收液体的建筑");
                    }
                    Jval data = Jval.newObject();
                    data.put("type", "liquid");
                    data.put("id", id);
                    data.put("spawned", placed);
                    data.put("x", x);
                    data.put("y", y);
                    data.put("size", size);
                    data.put("teamId", teamId);
                    return YZFResponse.ok("world.spawn.liquid_ok", "已向 " + placed + " 个建筑注入液体 " + liquid.name, data);
                }
                default -> {
                    return YZFResponse.fail("world.spawn.unsupported", "未知类型: " + type + "，支持 unit, block, floor, liquid, overlay");
                }
            }
        }catch(Exception e){
            return YZFResponse.fail("world.spawn.error", "错误: " + e.getMessage());
        }
    }

    // ========== yzf.content.registerMeta / setProperty / getProperty ==========

    public void contentRegisterMeta(String namespace, String name, String json){
        MindustryYZF.context().contentRegistry.registerMeta(namespace, name, json);
    }

    public String contentGetMeta(String namespace, String name){
        return MindustryYZF.context().contentRegistry.getMeta(namespace, name);
    }

    public String contentListMeta(String namespace){
        return MindustryYZF.context().contentRegistry.listMeta(namespace);
    }

    public String contentListNamespaces(){
        return MindustryYZF.context().contentRegistry.listNamespaces();
    }

    public boolean contentRemoveMeta(String namespace, String name){
        return MindustryYZF.context().contentRegistry.removeMeta(namespace, name);
    }

    public String contentSetProperty(String contentName, String property, String value){
        return MindustryYZF.context().contentRegistry.setProperty(contentName, property, value);
    }

    public String contentGetProperty(String contentName, String property){
        return MindustryYZF.context().contentRegistry.getProperty(contentName, property);
    }

    // ========== yzf.ws.* — WebSocket ==========
    public String wsConnect(String url, Object onOpen, Object onMessage, Object onClose, Object onError){
        YZFWebSocketManager wsManager = MindustryYZF.context().wsManager;
        Function openFn = onOpen instanceof Function ? (Function)onOpen : null;
        Function msgFn = onMessage instanceof Function ? (Function)onMessage : null;
        Function closeFn = onClose instanceof Function ? (Function)onClose : null;
        Function errFn = onError instanceof Function ? (Function)onError : null;
        return wsManager.connect(module.fullId(), url, scope, openFn, msgFn, closeFn, errFn);
    }

    public boolean wsSend(String connectionId, String message){
        return MindustryYZF.context().wsManager.sendText(connectionId, message);
    }

    public boolean wsSendBinary(String connectionId, String base64Data){
        return MindustryYZF.context().wsManager.sendBinary(connectionId, base64Data);
    }

    public void wsClose(String connectionId){
        MindustryYZF.context().wsManager.close(connectionId);
    }

    public boolean wsIsOpen(String connectionId){
        return MindustryYZF.context().wsManager.isOpen(connectionId);
    }

    public String wsList(){
        String[] ids = MindustryYZF.context().wsManager.listConnections();
        Jval array = Jval.newArray();
        for(String id : ids){
            Jval obj = Jval.newObject();
            obj.put("id", id);
            obj.put("url", MindustryYZF.context().wsManager.getUrl(id));
            obj.put("open", MindustryYZF.context().wsManager.isOpen(id));
            array.add(obj);
        }
        return array.toString(Jval.Jformat.plain);
    }

    // ========== yzf.comid.* — comid 系统 ==========

    public long comidGet(String uuid){
        return MindustryYZF.context().comidRegistry.getComid(uuid);
    }

    public long comidGetOrCreate(String uuid){
        return MindustryYZF.context().comidRegistry.getOrCreate(uuid);
    }

    public String comidGetUuid(long comid){
        return MindustryYZF.context().comidRegistry.getUuid(comid);
    }

    public boolean comidExists(long comid){
        return MindustryYZF.context().comidRegistry.exists(comid);
    }

    public int comidDigits(){
        return MindustryYZF.context().comidRegistry.currentDigits();
    }

    public long comidRemaining(){
        return MindustryYZF.context().comidRegistry.remainingInCurrentDigits();
    }

    public int comidTotal(){
        return MindustryYZF.context().comidRegistry.totalRegistered();
    }

    // ========== yzf.player.data.* — 玩家数据持久化 ==========

    public String playerDataGet(long comid, String key){
        return MindustryYZF.context().playerDataStore.get(comid, key);
    }

    public String playerDataGetDefault(long comid, String key, String defaultValue){
        return MindustryYZF.context().playerDataStore.get(comid, key, defaultValue);
    }

    public void playerDataSet(long comid, String key, String value){
        MindustryYZF.context().playerDataStore.set(comid, key, value);
    }

    public int playerDataGetInt(long comid, String key, int defaultValue){
        return MindustryYZF.context().playerDataStore.getInt(comid, key, defaultValue);
    }

    public void playerDataSetInt(long comid, String key, int value){
        MindustryYZF.context().playerDataStore.setInt(comid, key, value);
    }

    public boolean playerDataGetBool(long comid, String key, boolean defaultValue){
        return MindustryYZF.context().playerDataStore.getBool(comid, key, defaultValue);
    }

    public void playerDataSetBool(long comid, String key, boolean value){
        MindustryYZF.context().playerDataStore.setBool(comid, key, value);
    }

    public double playerDataGetDouble(long comid, String key, double defaultValue){
        return MindustryYZF.context().playerDataStore.getDouble(comid, key, defaultValue);
    }

    public void playerDataSetDouble(long comid, String key, double value){
        MindustryYZF.context().playerDataStore.setDouble(comid, key, value);
    }

    public String playerDataGetAll(long comid){
        return MindustryYZF.context().playerDataStore.getAll(comid);
    }

    public void playerDataRemove(long comid, String key){
        MindustryYZF.context().playerDataStore.remove(comid, key);
    }

    public void playerDataClear(long comid){
        MindustryYZF.context().playerDataStore.clear(comid);
    }

    // ========== yzf.db.* - unified JSON database layer ==========

    public String dbList(){
        return MindustryYZF.context().databaseRegistry.listJson();
    }

    public String dbInfo(String id){
        return MindustryYZF.context().databaseRegistry.infoJson(id);
    }

    public boolean dbHas(String id){
        return MindustryYZF.context().databaseRegistry.has(id);
    }

    public boolean dbAddLocal(String id, String name){
        return MindustryYZF.context().databaseRegistry.addLocal(id, name);
    }

    public boolean dbAddRemote(String id, String name, String endpoint, String serviceId, boolean readOnly){
        return MindustryYZF.context().databaseRegistry.addRemote(id, name, endpoint, serviceId, readOnly);
    }

    public boolean dbRemove(String id){
        return MindustryYZF.context().databaseRegistry.remove(id);
    }

    public String dbCategories(String id) throws Exception{
        return MindustryYZF.context().databaseRegistry.categoriesJson(id);
    }

    public String dbKeys(String id, String category) throws Exception{
        return MindustryYZF.context().databaseRegistry.keysJson(id, category);
    }

    public String dbGet(String id, String category, String key) throws Exception{
        return MindustryYZF.context().databaseRegistry.get(id, category, key);
    }

    public void dbSet(String id, String category, String key, String valueJson) throws Exception{
        MindustryYZF.context().databaseRegistry.set(id, category, key, valueJson);
    }

    public boolean dbRemoveEntry(String id, String category, String key) throws Exception{
        return MindustryYZF.context().databaseRegistry.removeEntry(id, category, key);
    }

    public String dbDump(String id) throws Exception{
        return MindustryYZF.context().databaseRegistry.dumpJson(id);
    }

    public void dbImport(String id, String json) throws Exception{
        MindustryYZF.context().databaseRegistry.importJson(id, json);
    }

    public String dbDefaultId(){
        return MindustryYZF.context().databaseRegistry.defaultId();
    }

    public int dbCount(){
        return MindustryYZF.context().databaseRegistry.count();
    }

    // ========== yzf.module.export/call — 跨模块通信 ==========
    // 已导出的函数: moduleId -> {fnName -> Function}
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Map<String, Function>> exportedFunctions = new java.util.concurrent.ConcurrentHashMap<>();

    public void moduleExport(String fnName, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.module.export 的回调必须是函数。");
        }
        String moduleId = module.fullId();
        exportedFunctions.computeIfAbsent(moduleId, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(fnName, (Function)callback);
    }

    public Object moduleCall(String targetModuleId, String fnName, Object[] args){
        // Java/Kotlin embedded modules and JavaScript modules share the same
        // module.call API. Try an embedded export before the JS registry.
        try{
            return runtime.callEmbeddedExport(targetModuleId, fnName, args);
        }catch(IllegalArgumentException ignored){
            // The target may be a JavaScript module; resolve it below.
        }
        java.util.Map<String, Function> fns = exportedFunctions.get(targetModuleId);
        if(fns == null){
            throw new IllegalArgumentException("模块 " + targetModuleId + " 没有导出任何函数。");
        }
        Function fn = fns.get(fnName);
        if(fn == null){
            throw new IllegalArgumentException("模块 " + targetModuleId + " 没有导出函数 '" + fnName + "'。");
        }
        // 在目标模块的 scope 中调用
        YZFLoadedModule targetState = runtime.getLoadedModule(targetModuleId);
        if(targetState == null){
            throw new IllegalArgumentException("模块 " + targetModuleId + " 未加载。");
        }
        rhino.Context ctx = rhino.Context.enter();
        try{
            return fn.call(ctx, targetState.scope, targetState.scope, args != null ? args : new Object[0]);
        }finally{
            rhino.Context.exit();
        }
    }

    public String moduleExportedFunctions(String targetModuleId){
        java.util.Map<String, Function> fns = exportedFunctions.get(targetModuleId);
        Jval array = Jval.newArray();
        if(fns != null){
            for(String name : fns.keySet()){
                array.add(name);
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    // ========== yzf.commands.* — 可调用命令注册 ==========

    public void commandsRegister(String name, String description, Object callback, Object scopeObj){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.commands.register 的回调必须是函数。");
        }
        Scriptable scopeToUse = scopeObj instanceof Scriptable ? (Scriptable)scopeObj : this.scope;
        MindustryYZF.context().commandRegistry.register(module.fullId(), name, description, (Function)callback, scopeToUse);
    }

    public Object commandsCall(String name, Object[] args){
        return MindustryYZF.context().commandRegistry.call(name, args);
    }

    public boolean commandsHas(String name){
        return MindustryYZF.context().commandRegistry.has(name);
    }

    public void commandsUnregister(String name){
        MindustryYZF.context().commandRegistry.unregister(module.fullId(), name);
    }

    public String commandsList(){
        return MindustryYZF.context().commandRegistry.listAsJson();
    }

    public String commandsListModule(String moduleId){
        return MindustryYZF.context().commandRegistry.listModuleAsJson(moduleId);
    }

    public boolean commandsRun(String commandName, String[] args){
        return runtime.invokeServerCommand(commandName, args);
    }

    // ========== yzf.mod.* — 统一命令注册接口 ==========

    public void modRegisterServerCommand(String name, String usage, String description, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.mod.registerServerCommand 的回调必须是函数。");
        }
        String commandName = normalize(name);
        if(!YZFSecurity.validCommandName(commandName)){
            throw new IllegalArgumentException("非法命令名: " + commandName);
        }
        MindustryYZF.context().modCommands.registerServerCommand(module, commandName, usage == null ? "" : usage, description == null ? "" : description, (Function)callback);
        if(!commandNames.contains(commandName)){
            commandNames.add(commandName);
        }
    }

    public void modRegisterPlayerCommand(String name, String usage, String description, boolean adminOnly, String permission, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.mod.registerPlayerCommand 的回调必须是函数。");
        }
        String commandName = normalize(name);
        if(!YZFSecurity.validCommandName(commandName)){
            throw new IllegalArgumentException("非法命令名: " + commandName);
        }
        String finalPermission = YZFText.blank(permission) ? module.meta.permission : permission;
        MindustryYZF.context().modCommands.registerPlayerCommand(module, commandName, usage == null ? "" : usage, description == null ? "" : description, adminOnly, finalPermission, (Function)callback);
        playerCommandNames.add(new YZFPlayerCommandBinding(commandName, adminOnly, finalPermission));
    }

    public void modRegisterCallableCommand(String name, String description, Object callback){
        if(!(callback instanceof Function)){
            throw new IllegalArgumentException("yzf.mod.registerCallableCommand 的回调必须是函数。");
        }
        MindustryYZF.context().modCommands.registerCallableCommand(module, name, description, (Function)callback, scope);
    }

    public boolean modUnregisterCommand(String name){
        return MindustryYZF.context().modCommands.unregisterCommand(module.fullId(), name);
    }

    public String modListCommands(){
        return MindustryYZF.context().modCommands.listCommands(module.fullId());
    }

    public boolean modHasCommand(String name){
        return MindustryYZF.context().modCommands.hasCommand(name);
    }

    public YZFLoadedModule freeze(String sourceText){
        return new YZFLoadedModule(module, commandNames.copy(), playerCommandNames.copy(), eventBindings.copy(), taskBindings.copy(), onEnable, onDisable, scope, sourceText);
    }

    void discard(){
        runtime.discardModuleResources(module.fullId(), commandNames.copy(), playerCommandNames.copy(), eventBindings.copy(), taskBindings.copy());
    }

    /** 在模块 scope 中评估 JS 文件，返回 true 表示成功，false 表示失败（异常被内部捕获） */
    public boolean evalFile(String filePath){
        try{
            java.io.File f = new java.io.File(filePath);
            if(!f.exists()) return false;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.InputStreamReader isr = new java.io.InputStreamReader(fis, "UTF-8");
            java.io.BufferedReader br = new java.io.BufferedReader(isr);
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            String line;
            while((line = br.readLine()) != null){
                sb.append(line).append("\n");
            }
            br.close(); isr.close(); fis.close();
            rhino.Context ctx = rhino.Context.enter();
            try{
                ctx.evaluateString(scope, sb.toString(), filePath, 1);
            }finally{
                rhino.Context.exit();
            }
            return true;
        }catch(Throwable t){
            Log.err("[@] evalFile failed: @", MindustryYZF.name, t.getMessage());
            return false;
        }
    }

    /** 清理模块导出的跨模块函数（模块卸载时调用） */
    static void clearExportedFunctions(String moduleId){
        exportedFunctions.remove(moduleId);
    }

    static Object callExportedFunction(String targetModuleId, String fnName, Object[] args){
        java.util.Map<String, Function> fns = exportedFunctions.get(targetModuleId);
        if(fns == null){
            throw new IllegalArgumentException("Module has no exported functions: " + targetModuleId);
        }
        Function fn = fns.get(fnName);
        if(fn == null){
            throw new IllegalArgumentException("Exported function not found: " + targetModuleId + "/" + fnName);
        }
        YZFContext context = MindustryYZF.context();
        if(context == null || !(context.runtime instanceof YZFJsRuntime runtime)){
            throw new IllegalStateException("JS runtime is not available.");
        }
        YZFLoadedModule targetState = runtime.getLoadedModule(targetModuleId);
        if(targetState == null){
            throw new IllegalArgumentException("Target module is not loaded: " + targetModuleId);
        }
        rhino.Context ctx = rhino.Context.enter();
        try{
            return fn.call(ctx, targetState.scope, targetState.scope, args != null ? args : new Object[0]);
        }finally{
            rhino.Context.exit();
        }
    }

    static String listExportedFunctionsStatic(String targetModuleId){
        java.util.Map<String, Function> fns = exportedFunctions.get(targetModuleId);
        Jval array = Jval.newArray();
        if(fns != null){
            for(String name : fns.keySet()){
                array.add(name);
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    /** 清理模块创建的 WebSocket 连接（模块卸载时调用） */
    static void cleanupModuleWs(String moduleId){
        MindustryYZF.context().wsManager.closeModule(moduleId);
    }

    public Seq<String> commandNames(){
        return commandNames;
    }

    private String normalize(String input){
        if(YZFText.blank(input)) throw new IllegalArgumentException("命令名不能为空。");
        return input.trim().toLowerCase();
    }
}
