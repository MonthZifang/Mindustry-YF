package mindustry.yzf;

import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class YZFProcessRuntime{
    private static final long PROCESS_TIMEOUT_SECONDS = 120L;
    private final ObjectMap<String, YZFProcessModuleState> processes = new ObjectMap<>();

    public synchronized void reload(YZFModuleDefinition module){
        if(module.meta.runtime == null) return;
        String runtime = normalizeRuntime(module.meta.runtime);
        if(!(runtime.equals("java") || runtime.equals("kt") || runtime.equals("kts") || runtime.equals("node"))) return;

        if(!module.hasMain()){
            throw new IllegalStateException("进程模块缺少主脚本: " + module.fullId());
        }

        stop(module.fullId());

        Process process = null;
        YZFProcessModuleState state = null;
        try{
            ProcessBuilder builder = createBuilder(module, runtime);
            builder.directory(module.root.file());
            builder.environment().put("YZF_MODULE_ID", module.id());
            builder.environment().put("YZF_MODULE_FULL_ID", module.fullId());
            builder.environment().put("YZF_MODULE_ROOT", module.root.absolutePath());
            builder.environment().put("YZF_DATA_DIR", module.dataDir.absolutePath());
            builder.environment().put("YZF_CACHE_DIR", module.cacheDir.absolutePath());
            builder.environment().put("YZF_PROTOCOL", "ndjson-stdio");
            Process startedProcess = builder.start();
            process = startedProcess;

            YZFProtocolHost protocol = new YZFProtocolHost(process);
            YZFProcessModuleState[] stateRef = new YZFProcessModuleState[1];
            Thread protocolReader = new Thread(() -> readProtocol(stateRef), "YZF-" + runtime + "-" + module.id() + "-protocol");
            Thread stderr = new Thread(() -> readError(module, runtime, startedProcess), "YZF-" + runtime + "-" + module.id() + "-stderr");
            protocolReader.setDaemon(true);
            stderr.setDaemon(true);

            state = new YZFProcessModuleState(module, runtime, process, protocol, protocolReader, stderr, protocolReader);
            stateRef[0] = state;
            processes.put(module.fullId(), state);

            protocolReader.start();
            stderr.start();
            send(state, lifecycle("init", module));
            send(state, lifecycle("enable", module));

            MindustryYZF.context().audit.record("module-start", module.fullId(), runtime);
            Log.info("[@] 进程模块已启动: @ (运行时=@)", MindustryYZF.name, module.fullId(), runtime);
        }catch(IOException e){
            throw new IllegalStateException("启动运行时 '" + runtime + "' 失败。请确认已安装并加入 PATH。", e);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("准备进程模块时被中断: " + module.fullId(), e);
        }catch(Throwable e){
            if(state != null){
                stop(module.fullId());
            }else if(process != null){
                process.destroy();
                try{
                    if(!process.waitFor(750, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if(e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("进程模块启动失败: " + module.fullId(), e);
        }
    }

    public synchronized void stop(String moduleId){
        YZFProcessModuleState state = processes.remove(moduleId);
        if(state == null) return;

        try{
            send(state, lifecycle("disable", state.definition));
            send(state, lifecycle("shutdown", state.definition));
        }catch(Throwable error){
            YZFErrorLog.high(moduleId, "Process lifecycle shutdown callback failed", error);
        }

        cleanupBindings(state);
        state.protocol.close();
        state.process.destroy();
        try{
            if(!state.process.waitFor(750, TimeUnit.MILLISECONDS)){
                state.process.destroyForcibly();
            }
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            state.process.destroyForcibly();
        }
        joinQuietly(state.protocolReaderThread, 1000L, moduleId);
        joinQuietly(state.stderrThread, 1000L, moduleId);
        joinQuietly(state.stdoutThread, 1000L, moduleId);
        YZFContext context = MindustryYZF.context();
        if(context != null) context.audit.record("module-stop", moduleId, state.runtime);
    }

    private void joinQuietly(Thread thread, long timeout, String moduleId){
        if(thread == null || thread == Thread.currentThread()) return;
        try{
            thread.join(timeout);
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            YZFErrorLog.medium(moduleId, "Interrupted while waiting for process reader shutdown", error);
        }
    }

    public synchronized void stopAll(){
        for(String id : processes.keys().toSeq()){
            stop(id);
        }
    }

    public synchronized Seq<String> moduleIds(){
        return processes.keys().toSeq();
    }

    public synchronized int size(){
        return processes.size;
    }

    public synchronized java.util.List<java.util.Map<String, Object>> snapshot(){
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for(YZFProcessModuleState state : processes.values()){
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("moduleId", state.definition.fullId());
            item.put("runtime", state.runtime);
            item.put("pid", state.process.pid());
            item.put("alive", state.process.isAlive());
            item.put("memoryBytes", processMemoryBytes(state.process));
            String effectiveMin = MindustryYZF.context().runtimeConfig.effectiveMemoryMin(state.definition.meta.memoryMin);
            String effectiveMax = MindustryYZF.context().runtimeConfig.effectiveMemoryMax(state.definition.meta.memoryMax);
            item.put("memoryMin", effectiveMin);
            item.put("memoryMax", effectiveMax);
            boolean jvmProcess = state.runtime.equals("java") || state.runtime.equals("kt") || state.runtime.equals("kts");
            item.put("memoryMinEnforced", jvmProcess && !YZFText.blank(effectiveMin));
            item.put("memoryMaxEnforced", (jvmProcess || state.runtime.equals("node")) && !YZFText.blank(effectiveMax));
            item.put("memoryLimitEnforced", (jvmProcess || state.runtime.equals("node")) && (!YZFText.blank(effectiveMin) || !YZFText.blank(effectiveMax)));
            item.put("mainScript", portablePath(state.definition.mainScript));
            item.put("metaFile", portablePath(state.definition.metaFile));
            item.put("workingDirectory", portablePath(state.definition.root));
            item.put("sourceBytes", state.definition.mainScript.file().length());
            item.put("sourceText", YZFText.readTextSmart(state.definition.mainScript));
            item.put("command", state.process.info().command().orElse(""));
            item.put("commandLine", state.process.info().commandLine().orElse(""));
            item.put("commands", state.serverCommands.size);
            item.put("playerCommands", state.playerCommands.size);
            item.put("events", state.eventBindings.size);
            item.put("tasks", state.taskBindings.size);
            result.add(item);
        }
        return result;
    }

    private long processMemoryBytes(Process process){
        long pid = process.pid();
        try{
            if(System.getProperty("os.name", "").toLowerCase().contains("win")){
                Process probe = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH").redirectErrorStream(true).start();
                String text = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                probe.waitFor(2, TimeUnit.SECONDS);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([0-9,]+) K\\\"").matcher(text);
                if(matcher.find()) return Long.parseLong(matcher.group(1).replace(",", "")) * 1024L;
            }else{
                java.nio.file.Path status = java.nio.file.Path.of("/proc", String.valueOf(pid), "status");
                if(Files.isRegularFile(status)){
                    for(String line : Files.readAllLines(status)){
                        if(line.startsWith("VmRSS:")){
                            String digits = line.replaceAll("[^0-9]", "");
                            if(!digits.isEmpty()) return Long.parseLong(digits) * 1024L;
                        }
                    }
                }
            }
        }catch(Throwable error){
            YZFErrorLog.low("process-memory", "Unable to sample process memory for pid " + pid, error);
        }
        return -1L;
    }

    public synchronized boolean running(String moduleId){
        return processes.containsKey(moduleId);
    }

    public synchronized YZFModuleDefinition definition(String moduleId){
        YZFProcessModuleState state = processes.get(moduleId);
        return state == null ? null : state.definition;
    }

    private void readProtocol(YZFProcessModuleState[] stateRef){
        YZFProcessModuleState state = stateRef[0];
        if(state == null) return;
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(state.process.getInputStream(), StandardCharsets.UTF_8))){
            String line;
            while((line = reader.readLine()) != null){
                handleProtocolLine(state, line);
            }
        }catch(Exception e){
            if(state.process.isAlive()){
                Log.err("[@] 进程模块协议读取失败: @", MindustryYZF.name, state.definition.fullId(), e);
            }
        }finally{
            YZFMainThread.post(() -> cleanupExitedProcess(state));
        }
    }

    private synchronized void cleanupExitedProcess(YZFProcessModuleState state){
        String moduleId = state.definition.fullId();
        if(processes.get(moduleId) != state) return;
        processes.remove(moduleId);
        cleanupBindings(state);
        state.protocol.close();
        YZFContext context = MindustryYZF.context();
        if(context != null){
            context.audit.record("module-exit", moduleId, state.runtime + ": exit=" + safeExitValue(state.process));
        }
        Log.warn("[@] Process module exited and was cleaned up: @", MindustryYZF.name, moduleId);
    }

    private String safeExitValue(Process process){
        try{
            return String.valueOf(process.exitValue());
        }catch(IllegalThreadStateException ignored){
            return "unknown";
        }
    }

    private void readError(YZFModuleDefinition module, String runtime, Process process){
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))){
            String line;
            while((line = reader.readLine()) != null){
                Log.err("[@] [@/@] @", MindustryYZF.name, runtime, module.fullId(), line);
            }
        }catch(Exception error){
            YZFErrorLog.medium(module.fullId(), "Process stderr reader failed", error);
        }
    }

    private synchronized void handleProtocolLine(YZFProcessModuleState state, String line){
        if(YZFText.blank(line)) return;
        try{
            YZFProtocolMessage message = YZFProtocolMessage.parse(line);
            if(YZFText.blank(message.type)){
                Log.info("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), line);
                return;
            }

            MindustryYZF.context().metrics.protocolIn.incrementAndGet();
            switch(message.type){
                case "registerCommand" -> registerCommand(state, message);
                case "registerPlayerCommand" -> registerPlayerCommand(state, message, false);
                case "registerAdminCommand" -> registerPlayerCommand(state, message, true);
                case "unregisterCommand" -> unregisterCommand(state, message.field("name"));
                case "subscribeEvent" -> subscribeEvent(state, message);
                case "unsubscribeEvent" -> unsubscribeEvent(state, message.field("eventName"));
                case "schedule" -> scheduleTask(state, message);
                case "unschedule" -> unscheduleTask(state, message.field("id"));
                case "config.get" -> handleConfigGet(state, message);
                case "config.set" -> handleConfigSet(state, message);
                case "service.call" -> handleServiceCall(state, message);
                case "host.call" -> handleHostCall(state, message);
                case "log" -> handleLog(state, message);
                default -> Log.info("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), line);
            }
        }catch(Exception e){
            Log.info("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), line);
        }
    }

    private void registerCommand(YZFProcessModuleState state, YZFProtocolMessage message){
        String name = normalizeCommand(message.field("name"));
        String usage = empty(message.field("usage"));
        String description = empty(message.field("description"));
        String scope = YZFText.blank(message.field("scope")) ? "server" : message.field("scope").trim().toLowerCase();

        if(scope.equals("player")){
            registerPlayerCommand(state, message, false);
            return;
        }
        if(scope.equals("admin")){
            registerPlayerCommand(state, message, true);
            return;
        }

        CommandHandler handler = MindustryYZF.context().serverControl.handler;
        if(hasForeignServerCommand(state, name)){
            throw new IllegalStateException("服务端命令已存在: " + name);
        }
        handler.removeCommand(name);
        handler.register(name, usage, description, args -> {
            MindustryYZF.context().metrics.serverCommandCalls.incrementAndGet();
            sendCommandInvoke(state, "server", name, null, args);
        });
        if(!state.serverCommands.contains(name)){
            state.serverCommands.add(name);
        }
        reply(state, message.field("replyId"), true, "OK", null);
    }

    private void registerPlayerCommand(YZFProcessModuleState state, YZFProtocolMessage message, boolean adminOnly){
        String name = normalizeCommand(message.field("name"));
        String usage = empty(message.field("usage"));
        String description = empty(message.field("description"));
        String permission = empty(message.field("permission"));

        CommandHandler handler = Vars.netServer.clientCommands;
        if(hasForeignPlayerCommand(state, name)){
            throw new IllegalStateException("玩家命令已存在: " + name);
        }
        handler.removeCommand(name);
        handler.<Player>register(name, usage, description, (args, player) -> {
            if(adminOnly && (player == null || !player.admin)){
                if(player != null) player.sendMessage("[scarlet]该命令仅管理员可用。");
                return;
            }
            if(!YZFText.blank(permission) && !MindustryYZF.context().permissions.has(player, permission)){
                MindustryYZF.context().metrics.permissionDenied.incrementAndGet();
                MindustryYZF.context().audit.record("permission-denied", state.definition.fullId(), name + " -> " + permission);
                if(player != null) player.sendMessage("[scarlet]你没有权限使用该命令。");
                return;
            }
            MindustryYZF.context().metrics.playerCommandCalls.incrementAndGet();
            sendCommandInvoke(state, adminOnly ? "admin" : "player", name, player, args);
        });

        boolean present = false;
        for(YZFPlayerCommandBinding binding : state.playerCommands){
            if(binding.name.equals(name)){
                present = true;
                break;
            }
        }
        if(!present){
            state.playerCommands.add(new YZFPlayerCommandBinding(name, adminOnly, permission));
        }
        reply(state, message.field("replyId"), true, "OK", null);
    }

    private void unregisterCommand(YZFProcessModuleState state, String name){
        if(YZFText.blank(name)) return;

        if(state.serverCommands.remove(name, false)){
            MindustryYZF.context().serverControl.handler.removeCommand(name);
        }
        for(int i = state.playerCommands.size - 1; i >= 0; i--){
            YZFPlayerCommandBinding binding = state.playerCommands.get(i);
            if(binding.name.equals(name)){
                Vars.netServer.clientCommands.removeCommand(name);
                state.playerCommands.remove(i);
            }
        }
    }

    private void subscribeEvent(YZFProcessModuleState state, YZFProtocolMessage message){
        String eventName = empty(message.field("eventName"));
        if(YZFText.blank(eventName)) return;
        for(YZFEventBinding binding : state.eventBindings){
            if(binding.eventName.equalsIgnoreCase(eventName)){
                reply(state, message.field("replyId"), true, "OK", null);
                return;
            }
        }

        Class<?> eventType = YZFEventRegistry.find(eventName);
        if(eventType == null){
            throw new IllegalArgumentException("未知事件类型: " + eventName);
        }

        Cons<Object> handler = event -> sendEvent(state, eventName, event);
        Events.on((Class)eventType, (Cons)handler);
        state.eventBindings.add(new YZFEventBinding(eventName, eventType, handler));
        reply(state, message.field("replyId"), true, "OK", null);
    }

    private void unsubscribeEvent(YZFProcessModuleState state, String eventName){
        if(YZFText.blank(eventName)) return;
        for(int i = state.eventBindings.size - 1; i >= 0; i--){
            YZFEventBinding binding = state.eventBindings.get(i);
            if(binding.eventName.equalsIgnoreCase(eventName)){
                Events.remove((Class)binding.eventType, (Cons)binding.handler);
                state.eventBindings.remove(i);
            }
        }
    }

    private void scheduleTask(YZFProcessModuleState state, YZFProtocolMessage message){
        String id = empty(message.field("id"));
        if(YZFText.blank(id)) return;

        unscheduleTask(state, id);
        float delay = parseFloat(message.field("delaySeconds"), 0f);
        float interval = parseFloat(message.field("intervalSeconds"), 0f);
        Timer.Task task = interval > 0f ?
            Timer.schedule(() -> sendTaskFire(state, id), delay, interval) :
            Timer.schedule(() -> sendTaskFire(state, id), delay);
        state.taskBindings.add(new YZFTaskBinding(id, interval > 0f ? "repeat" : "once", task));
        reply(state, message.field("replyId"), true, "OK", null);
    }

    private void unscheduleTask(YZFProcessModuleState state, String id){
        if(YZFText.blank(id)) return;
        for(int i = state.taskBindings.size - 1; i >= 0; i--){
            YZFTaskBinding binding = state.taskBindings.get(i);
            if(binding.id.equals(id)){
                binding.cancel();
                state.taskBindings.remove(i);
            }
        }
    }

    private void handleConfigGet(YZFProcessModuleState state, YZFProtocolMessage message){
        String replyId = message.field("replyId");
        YZFModuleConfigStore store = new YZFModuleConfigStore(state.definition);
        reply(state, replyId, true, store.getString(empty(message.field("key")), empty(message.field("defaultValue"))), null);
    }

    private void handleConfigSet(YZFProcessModuleState state, YZFProtocolMessage message){
        String replyId = message.field("replyId");
        YZFModuleConfigStore store = new YZFModuleConfigStore(state.definition);
        String key = empty(message.field("key"));
        String kind = empty(message.field("kind"));
        String value = empty(message.field("value"));
        if("bool".equalsIgnoreCase(kind)){
            store.putBool(key, Boolean.parseBoolean(value));
        }else if("int".equalsIgnoreCase(kind)){
            store.putInt(key, Integer.parseInt(value));
        }else{
            store.putString(key, value);
        }
        reply(state, replyId, true, "OK", null);
    }

    private void handleServiceCall(YZFProcessModuleState state, YZFProtocolMessage message){
        String replyId = message.field("replyId");
        try{
            Seq<String> args = new Seq<>();
            for(int i = 0; i < 6; i++){
                String value = message.field("arg" + i);
                if(value != null) args.add(value);
            }
            String value = new YZFScriptServices(MindustryYZF.context()).serviceCall(
                empty(message.field("serviceId")),
                empty(message.field("action")),
                args.toArray(String.class)
            );
            reply(state, replyId, true, value, null);
        }catch(Exception e){
            reply(state, replyId, false, null, e.getMessage());
        }
    }

    private void handleHostCall(YZFProcessModuleState state, YZFProtocolMessage message){
        String replyId = message.field("replyId");
        String action = empty(message.field("action"));
        try{
            String value = switch(action){
                case "webui.status" -> webUiStatusJson();
                case "webui.players" -> webUiPlayersJson();
                case "webui.plugins" -> webUiPluginsJson();
                case "webui.databases" -> webUiDatabasesJson();
                case "webui.configs" -> webUiConfigsJson();
                case "webui.permissions" -> webUiPermissionsJson();
                case "webui.mapState" -> webUiMapStateJson();
                case "webui.player.kick" -> webUiKick(message.field("arg0"), message.field("arg1"));
                case "webui.player.ban" -> webUiBan(message.field("arg0"), message.field("arg1"));
                default -> throw new IllegalArgumentException("Unknown host action: " + action);
            };
            reply(state, replyId, true, value, null);
        }catch(Exception e){
            reply(state, replyId, false, null, e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private void handleLog(YZFProcessModuleState state, YZFProtocolMessage message){
        String level = empty(message.field("level")).toLowerCase();
        String text = empty(message.field("message"));
        if(level.equals("warn")){
            Log.warn("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), text);
        }else if(level.equals("error") || level.equals("err")){
            Log.err("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), text);
        }else{
            Log.info("[@] [@/@] @", MindustryYZF.name, state.runtime, state.definition.fullId(), text);
        }
    }

    private String webUiStatusJson(){
        Jval root = Jval.newObject();
        Jval server = Jval.newObject();
        root.put("ok", true);

        server.put("name", MindustryYZF.name);
        server.put("version", MindustryYZF.version);
        server.put("description", Vars.state.isMenu() ? "offline" : "running");
        server.put("players", Groups.player.size());
        server.put("playersPeak", Groups.player.size());
        server.put("tps", mindustry.server.ServerLauncher.actualTps());
        server.put("tpsAvg", mindustry.server.ServerLauncher.actualTps());
        server.put("tpsLimit", Vars.serverTps);
        server.put("memoryMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L / 1024L);
        server.put("memoryLimitMb", Runtime.getRuntime().maxMemory() / 1024L / 1024L);
        server.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        if(!Vars.state.isMenu() && Vars.state.map != null){
            server.put("map", Vars.state.map.plainName());
            server.put("wave", Vars.state.wave);
            server.put("mapWidth", Vars.world.width());
            server.put("mapHeight", Vars.world.height());
        }else{
            server.put("map", "--");
            server.put("wave", 0);
            server.put("mapWidth", 0);
            server.put("mapHeight", 0);
        }
        root.put("server", server);

        Jval api = Jval.newObject();
        api.put("source", "yzf-process-runtime");
        root.put("api", api);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiPlayersJson(){
        Jval root = Jval.newObject();
        Jval players = Jval.newArray();
        root.put("ok", true);
        Groups.player.each(player -> {
            Jval item = Jval.newObject();
            item.put("id", player.id);
            item.put("name", player.plainName());
            item.put("uuid", player.uuid() == null ? "" : player.uuid());
            item.put("comid", player.uuid() == null ? "" : String.valueOf(MindustryYZF.context().comidRegistry.getOrCreate(player.uuid())));
            item.put("op", player.admin);
            item.put("banned", player.uuid() != null && Vars.netServer.admins.isIDBanned(player.uuid()));
            item.put("playTime", "--");
            item.put("ip", player.ip() == null ? "" : player.ip());
            item.put("unitName", player.unit() == null || player.unit().type == null ? "" : String.valueOf(player.unit().type.name));
            item.put("team", player.team().id);
            item.put("teamName", String.valueOf(player.team().name));
            players.add(item);
        });
        root.put("players", players);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiPluginsJson(){
        Jval root = Jval.newObject();
        Jval plugins = Jval.newArray();
        root.put("ok", true);
        for(YZFModuleDefinition module : MindustryYZF.context().registry.modules()){
            if(!"plugins".equalsIgnoreCase(module.meta._source)) continue;
            Jval item = Jval.newObject();
            item.put("id", module.fullId());
            item.put("name", module.meta.name == null ? module.id() : module.meta.name);
            item.put("author", module.meta.author == null ? "" : module.meta.author);
            item.put("version", module.meta.version == null ? "" : module.meta.version);
            item.put("description", module.meta.description == null ? "" : module.meta.description);
            item.put("category", module.meta.category == null ? "Other" : module.meta.category);
            item.put("enabled", module.meta.enabled);
            item.put("updateDate", "");
            plugins.add(item);
        }
        root.put("plugins", plugins);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiDatabasesJson(){
        Jval root = Jval.newObject();
        Jval items = Jval.newArray();
        root.put("ok", true);
        try{
            Jval source = Jval.read(MindustryYZF.context().databaseRegistry.listJson());
            if(source != null && source.isArray()){
                for(Jval entry : source.asArray()){
                    if(entry == null || !entry.isObject()) continue;
                    Jval item = Jval.newObject();
                    item.put("id", entry.getString("id", ""));
                    item.put("title", entry.getString("name", entry.getString("id", "")));
                    item.put("type", entry.getString("type", ""));
                    item.put("endpoint", entry.getString("endpoint", entry.getString("sourcePath", "")));
                    item.put("databaseFile", entry.getString("sourcePath", ""));
                    item.put("active", entry.getBool("enabled", true));
                    items.add(item);
                }
            }
        }catch(Exception ignored){
        }
        root.put("databases", items);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiConfigsJson(){
        YZFContext context = MindustryYZF.context();
        Jval root = Jval.newObject();
        Jval configs = Jval.newArray();
        root.put("ok", true);

        addConfigEntry(configs, "yzf-security", "YZF Security", context.paths.relative(context.paths.securityFile));
        addConfigEntry(configs, "yzf-services", "YZF Services", context.paths.relative(context.paths.servicesDir));
        addConfigEntry(configs, "yzf-remotes", "YZF Remotes", context.paths.relative(context.paths.remotesDir));
        addConfigEntry(configs, "yzf-permissions", "YZF Permissions", context.paths.relative(context.paths.permissionsFile));
        addConfigEntry(configs, "yzf-databases", "YZF Databases", context.paths.relative(context.paths.databaseRegistryFile));

        for(YZFModuleDefinition module : context.registry.modules()){
            if(!"plugins".equalsIgnoreCase(module.meta._source)) continue;
            if(module.root == null) continue;
            if(module.dataDir != null && module.dataDir.child("config").child("config.hjson").exists()){
                addConfigEntry(configs, module.id() + "-config", module.meta.name == null ? module.id() : module.meta.name, context.paths.relative(module.dataDir.child("config").child("config.hjson")));
            }
        }

        root.put("configs", configs);
        return root.toString(Jval.Jformat.plain);
    }

    private void addConfigEntry(Jval configs, String id, String name, String path){
        if(YZFText.blank(path)) return;
        Jval item = Jval.newObject();
        item.put("id", id);
        item.put("name", name);
        item.put("path", path);
        configs.add(item);
    }

    private String webUiPermissionsJson(){
        Jval root = Jval.newObject();
        Jval roles = Jval.newArray();
        root.put("ok", true);
        for(String role : MindustryYZF.context().permissions.roles()){
            roles.add(role);
        }
        root.put("roles", roles);
        root.put("path", MindustryYZF.context().paths.relative(MindustryYZF.context().paths.permissionsFile));
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiMapStateJson(){
        Jval root = Jval.newObject();
        root.put("ok", true);
        root.put("mapWidth", Vars.state.isMenu() ? 0 : Vars.world.width());
        root.put("mapHeight", Vars.state.isMenu() ? 0 : Vars.world.height());
        root.put("wave", Vars.state.wave);
        Jval players = Jval.newArray();
        Groups.player.each(player -> {
            Jval item = Jval.newObject();
            item.put("id", player.id);
            item.put("name", player.plainName());
            item.put("x", (int)player.x);
            item.put("y", (int)player.y);
            item.put("team", player.team().id);
            players.add(item);
        });
        root.put("players", players);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiKick(String uuid, String reason){
        if(YZFText.blank(uuid)) throw new IllegalArgumentException("uuid is required");
        Player player = findPlayerByUuid(uuid);
        boolean ok = false;
        if(player != null){
            player.kick(reason == null ? "" : reason);
            ok = true;
        }
        Jval root = Jval.newObject();
        root.put("ok", ok);
        root.put("uuid", uuid);
        return root.toString(Jval.Jformat.plain);
    }

    private String webUiBan(String uuid, String reason){
        if(YZFText.blank(uuid)) throw new IllegalArgumentException("uuid is required");
        boolean banned = Vars.netServer.admins.banPlayerID(uuid);
        Player player = findPlayerByUuid(uuid);
        if(player != null){
            player.kick(reason == null ? "banned" : reason);
        }
        Jval root = Jval.newObject();
        root.put("ok", banned);
        root.put("uuid", uuid);
        return root.toString(Jval.Jformat.plain);
    }

    private Player findPlayerByUuid(String uuid){
        if(YZFText.blank(uuid)) return null;
        for(Player player : Groups.player){
            if(uuid.equals(player.uuid())) return player;
        }
        return null;
    }

    private void sendCommandInvoke(YZFProcessModuleState state, String scope, String name, Player player, String[] args){
        YZFProtocolMessage message = new YZFProtocolMessage();
        message.type = "command.invoke";
        message.fields.put("scope", scope);
        message.fields.put("name", name);
        message.fields.put("argsJson", encodeArgs(args));
        if(player != null){
            message.fields.put("playerName", player.name);
            message.fields.put("playerUuid", player.uuid());
            message.fields.put("playerAdmin", String.valueOf(player.admin));
            long comid = player.uuid() == null ? -1 : MindustryYZF.context().comidRegistry.getOrCreate(player.uuid());
            if(comid >= 0){
                message.fields.put("playerComid", String.valueOf(comid));
            }
        }
        send(state, message);
    }

    private void sendEvent(YZFProcessModuleState state, String eventName, Object event){
        YZFProtocolMessage message = new YZFProtocolMessage();
        message.type = "event";
        message.fields.put("eventName", eventName);
        message.fields.put("payloadJson", serializeEvent(event));
        message.fields.put("summary", String.valueOf(event));
        send(state, message);
    }

    private void sendTaskFire(YZFProcessModuleState state, String id){
        YZFProtocolMessage message = new YZFProtocolMessage();
        message.type = "task.fire";
        message.fields.put("id", id);
        send(state, message);
    }

    private void reply(YZFProcessModuleState state, String replyId, boolean ok, String value, String error){
        if(YZFText.blank(replyId)) return;
        YZFProtocolMessage reply = new YZFProtocolMessage();
        reply.type = "reply";
        reply.fields.put("replyId", replyId);
        reply.fields.put("ok", String.valueOf(ok));
        if(value != null) reply.fields.put("value", value);
        if(error != null) reply.fields.put("error", error);
        send(state, reply);
    }

    private void send(YZFProcessModuleState state, YZFProtocolMessage message){
        state.protocol.send(message);
        MindustryYZF.context().metrics.protocolOut.incrementAndGet();
    }

    private YZFProtocolMessage lifecycle(String type, YZFModuleDefinition module){
        YZFProtocolMessage message = new YZFProtocolMessage();
        message.type = type;
        message.fields.put("moduleId", module.id());
        message.fields.put("fullId", module.fullId());
        message.fields.put("name", module.meta.name);
        message.fields.put("author", module.author());
        message.fields.put("version", module.meta.version);
        message.fields.put("runtime", module.meta.runtime);
        message.fields.put("root", ".");
        message.fields.put("scriptsDir", modulePath(module, module.scriptsDir));
        message.fields.put("dataDir", modulePath(module, module.dataDir));
        message.fields.put("cacheDir", modulePath(module, module.cacheDir));
        message.fields.put("configPath", modulePath(module, module.dataDir.child("config.hjson")));
        message.fields.put("main", modulePath(module, module.mainScript));
        return message;
    }

    private String portablePath(Fi file){
        YZFContext context = MindustryYZF.context();
        return context == null ? file.name() : context.paths.relative(file);
    }

    private String modulePath(YZFModuleDefinition module, Fi file){
        try{
            java.nio.file.Path base = module.root.file().toPath().toAbsolutePath().normalize();
            java.nio.file.Path target = file.file().toPath().toAbsolutePath().normalize();
            if(target.startsWith(base)) return base.relativize(target).toString().replace('\\', '/');
        }catch(Exception ignored){
        }
        return file.name();
    }

    private void cleanupBindings(YZFProcessModuleState state){
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
    }

    private ProcessBuilder createBuilder(YZFModuleDefinition module, String runtime) throws IOException, InterruptedException{
        if(runtime.equals("node")){
            List<String> command = new ArrayList<>();
            command.add("node");
            command.add("--enable-source-maps");
            long nodeMax = memoryBytes(MindustryYZF.context().runtimeConfig.effectiveMemoryMax(module.meta.memoryMax));
            if(nodeMax > 0) command.add("--max-old-space-size=" + Math.max(1L, nodeMax / (1024L * 1024L)));
            command.add(module.mainScript.absolutePath());
            appendArgs(command, module.meta.programArgs);
            return new ProcessBuilder(command);
        }

        if(runtime.equals("java")){
            String extension = module.mainScript.extension().toLowerCase();
            List<String> command = createJavaCommand(module);
            if(extension.equals("jar")){
                command.add("-jar");
            }else{
                command.add(module.mainScript.absolutePath());
            }
            if(extension.equals("jar")){
                command.add(module.mainScript.absolutePath());
            }
            appendArgs(command, module.meta.programArgs);
            return new ProcessBuilder(command);
        }

        String extension = module.mainScript.extension().toLowerCase();
        if(extension.equals("jar")){
            List<String> command = createJavaCommand(module);
            command.add("-jar");
            command.add(module.mainScript.absolutePath());
            appendArgs(command, module.meta.programArgs);
            return new ProcessBuilder(command);
        }
        if(extension.equals("kts")){
            List<String> command = new ArrayList<>();
            command.add(resolveKotlincExecutable());
            for(String arg : module.meta.jvmArgs){
                if(arg != null && !arg.trim().isEmpty()){
                    command.add("-J" + arg.trim());
                }
            }
            command.add("-script");
            command.add(module.mainScript.absolutePath());
            appendArgs(command, module.meta.programArgs);
            return new ProcessBuilder(command);
        }

        String outJar = module.cacheDir.child(module.id() + "-runtime.jar").absolutePath();
        List<String> compileCommand = new ArrayList<>();
        compileCommand.add(resolveKotlincExecutable());
        for(String arg : module.meta.jvmArgs){
            if(arg != null && !arg.trim().isEmpty()){
                compileCommand.add("-J" + arg.trim());
            }
        }
        compileCommand.add(module.mainScript.absolutePath());
        compileCommand.add("-include-runtime");
        compileCommand.add("-d");
        compileCommand.add(outJar);
        File compileLog = module.cacheDir.child("kotlin-compile.log").file();
        Process compile = new ProcessBuilder(compileCommand)
            .directory(module.root.file())
            .redirectErrorStream(true)
            .redirectOutput(compileLog)
            .start();
        boolean finished = compile.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if(!finished){
            compile.destroy();
            if(!compile.waitFor(2, TimeUnit.SECONDS)) compile.destroyForcibly();
            throw new IllegalStateException("Kotlin compilation timed out for " + module.fullId());
        }
        String output = compileLog.isFile() ? Files.readString(compileLog.toPath(), StandardCharsets.UTF_8).trim() : "";
        int code = compile.exitValue();
        if(code != 0){
            throw new IllegalStateException("Failed to compile Kotlin module " + module.fullId() + ": " + output);
        }
        List<String> command = createJavaCommand(module);
        command.add("-jar");
        command.add(outJar);
        appendArgs(command, module.meta.programArgs);
        return new ProcessBuilder(command);
    }

    private List<String> createJavaCommand(YZFModuleDefinition module){
        List<String> command = new ArrayList<>();
        command.add("java");
        boolean hasMin = false;
        boolean hasMax = false;
        for(String arg : module.meta.jvmArgs){
            if(arg != null && !arg.trim().isEmpty()){
                String value = arg.trim();
                hasMin |= value.startsWith("-Xms");
                hasMax |= value.startsWith("-Xmx");
                command.add(value);
            }
        }
        long min = memoryBytes(MindustryYZF.context().runtimeConfig.effectiveMemoryMin(module.meta.memoryMin));
        long max = memoryBytes(MindustryYZF.context().runtimeConfig.effectiveMemoryMax(module.meta.memoryMax));
        if(min > 0 && !hasMin) command.add("-Xms" + min);
        if(max > 0 && !hasMax) command.add("-Xmx" + max);
        return command;
    }

    private long memoryBytes(String value){
        return YZFMemoryRegionManager.parseBytes(value);
    }

    private void appendArgs(List<String> command, Seq<String> args){
        for(String arg : args){
            if(arg != null && !arg.trim().isEmpty()){
                command.add(arg.trim());
            }
        }
    }

    private String resolveKotlincExecutable(){
        String direct = findExecutableOnPath("kotlinc.bat");
        if(direct != null) return direct;
        direct = findExecutableOnPath("kotlinc");
        if(direct != null) return direct;

        String explicit = System.getenv("KOTLINC");
        if(validFile(explicit)) return explicit;

        String home = System.getenv("KOTLIN_HOME");
        String fromHome = childExecutable(home, "bin", "kotlinc.bat");
        if(fromHome != null) return fromHome;
        fromHome = childExecutable(home, "bin", "kotlinc");
        if(fromHome != null) return fromHome;

        home = System.getenv("KOTLINC_HOME");
        fromHome = childExecutable(home, "bin", "kotlinc.bat");
        if(fromHome != null) return fromHome;
        fromHome = childExecutable(home, "bin", "kotlinc");
        if(fromHome != null) return fromHome;

        String path = System.getenv("PATH");
        if(path != null){
            String[] entries = path.split(File.pathSeparator);
            for(String entry : entries){
                if(entry == null || entry.isBlank()) continue;
                File dir = new File(entry);
                if(!dir.exists()) continue;
                File root = dir.getName().equalsIgnoreCase("bin") ? dir.getParentFile() : dir;
                if(root == null) continue;
                File candidate = new File(root, "plugins/Kotlin/kotlinc/bin/kotlinc.bat");
                if(candidate.isFile()) return candidate.getAbsolutePath();
            }
        }
        String configured = MindustryYZF.context().runtimeConfig.resolveCompiler(MindustryYZF.context().paths);
        if(configured != null) return configured;
        return "kotlinc";
    }

    private String findExecutableOnPath(String name){
        String path = System.getenv("PATH");
        if(path == null) return null;
        for(String entry : path.split(File.pathSeparator)){
            if(entry == null || entry.isBlank()) continue;
            File candidate = new File(entry, name);
            if(candidate.isFile()) return candidate.getAbsolutePath();
        }
        return null;
    }

    private String childExecutable(String root, String... parts){
        if(root == null || root.isBlank()) return null;
        File file = new File(root);
        for(String part : parts){
            file = new File(file, part);
        }
        return file.isFile() ? file.getAbsolutePath() : null;
    }

    private boolean validFile(String path){
        return path != null && !path.isBlank() && new File(path).isFile();
    }

    private String normalizeRuntime(String runtime){
        if(runtime == null) return "";
        String value = runtime.trim().toLowerCase();
        return value.equals("kts") ? "kt" : value;
    }

    private boolean hasForeignServerCommand(YZFProcessModuleState state, String name){
        for(CommandHandler.Command command : MindustryYZF.context().serverControl.handler.getCommandList()){
            if(command.text.equalsIgnoreCase(name)){
                return !state.serverCommands.contains(name);
            }
        }
        return false;
    }

    private boolean hasForeignPlayerCommand(YZFProcessModuleState state, String name){
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

    private String normalizeCommand(String input){
        if(YZFText.blank(input)) throw new IllegalArgumentException("命令名不能为空。");
        String value = input.trim().toLowerCase();
        if(!YZFSecurity.validCommandName(value)){
            throw new IllegalArgumentException("非法命令名: " + value);
        }
        return value;
    }

    private String empty(String value){
        return value == null ? "" : value;
    }

    private float parseFloat(String value, float fallback){
        if(YZFText.blank(value)) return fallback;
        try{
            return Float.parseFloat(value);
        }catch(Exception ignored){
            return fallback;
        }
    }

    private String encodeArgs(String[] args){
        Jval array = Jval.newArray();
        for(String arg : args){
            array.add(arg);
        }
        return array.toString(Jval.Jformat.plain);
    }

    private String serializeEvent(Object event){
        Jval root = Jval.newObject();
        root.put("_type", event.getClass().getSimpleName());
        for(Field field : event.getClass().getFields()){
            try{
                Object value = field.get(event);
                root.put(field.getName(), value == null ? null : String.valueOf(value));
            }catch(Exception ignored){
            }
        }
        try{
            Field playerField = event.getClass().getField("player");
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

    private String readText(Process process) throws IOException{
        StringBuilder out = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))){
            String line;
            while((line = reader.readLine()) != null){
                out.append(line).append('\n');
            }
        }
        return out.toString().trim();
    }
}
