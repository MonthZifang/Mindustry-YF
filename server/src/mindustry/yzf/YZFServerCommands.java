package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.YZFNetworkMetrics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YZFServerCommands{
    private static final int helpPageSize = 15;
    private static final int playerPageSize = 15;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final Pattern hexColorPattern = Pattern.compile("\\[#([0-9a-fA-F]{6,8})\\]");

    private YZFServerCommands(){
    }

    public static void register(YZFContext context){
        CommandHandler handler = context.serverControl.handler;
        handler.register(
            "yzf",
            "[help|status|health|metrics|scan|watch|reload|modules|plugins|mod|info|enable|disable|plugin|commands|services|service|permissions|runtime|audit|verify|api|players|dbs|<databaseAlias>|uuid] [args...]",
            "MindustryYZF 服务端控制命令。使用 `yzf mod help` 查看模块子命令。",
            args -> handleRoot(context, args)
        );
    }

    private static void handleRoot(YZFContext context, String[] args){
        if(args.length == 0){
            printHelp(null);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch(action){
            case "help", "帮助" -> printHelp(args.length >= 2 ? args[1] : null);
            case "status", "状态" -> printStructuredStatus(context);
            case "health", "健康" -> printHealth(context);
            case "metrics", "指标" -> printMetrics(context);
            case "scan", "扫描" -> {
                context.registry.scan();
                Log.info("[@] 模块目录扫描完成。模块数=@ 脚本数=@", MindustryYZF.name, context.registry.moduleCount(), context.registry.scriptCount());
            }
            case "watch", "监听" -> handleWatch(context, args);
            case "hotmods", "reloadmods" -> MindustryYZF.reloadExternalMods();
            case "reload", "重载" -> {
                if(args.length >= 2) context.runtime.reloadModule(args[1]);
                else context.runtime.reloadAll();
            }
            case "modules", "模块" -> printModules(context);
            case "info", "详情" -> printModuleInfo(context, args);
            case "enable", "启用" -> toggleModule(context, args, true);
            case "disable", "禁用" -> toggleModule(context, args, false);
            case "plugins", "插件" -> printPlugins(context);
            case "plugin", "插件管理" -> handlePlugin(context, args);
            case "commands", "命令" -> printCommands(context);
            case "mod", "模块管理" -> handleMod(context, args);
            case "services", "服务" -> printServices(context);
            case "service", "服务详情" -> handleServiceAction(context, args);
            case "permissions", "权限" -> handlePermissions(context, args);
            case "runtime", "运行时" -> printRuntime(context);
            case "audit", "审计" -> printAudit(context, args);
            case "verify", "验证" -> printVerify(context);
            case "api", "开放", "openapi" -> handleOpenApiCommand(context, slice(args, 1));
            case "players", "玩家" -> printDetailedPlayers(context, args);
            case "dbs", "databases", "数据库" -> printDatabases(context);
            case "uuid" -> printDatabasePlayersWithUuid(context, args);
            default -> {
                String resolvedDatabaseId = resolveDatabaseAlias(context, action);
                if(resolvedDatabaseId != null){
                    printDatabasePlayers(context, resolvedDatabaseId, args, false);
                }else{
                    Log.err("未知的 yzf 子命令 '@'。请使用 `yzf help` 查看帮助。", action);
                }
            }
        }
    }

    private static void printHelp(String pageArg){
        HelpEntry[] entries = helpEntries();

        if(pageArg != null && pageArg.equalsIgnoreCase("all")){
            Log.info("[@] yzf 命令列表（全部）", MindustryYZF.name);
            for(HelpEntry entry : entries){
                Log.info("  @ - @", entry.usage, entry.description);
            }
            return;
        }

        int page = parsePageArg(pageArg);
        int pages = Math.max(1, (entries.length + helpPageSize - 1) / helpPageSize);
        if(page > pages){
            Log.err("页码超出范围。当前总页数=@。", pages);
            return;
        }

        int start = (page - 1) * helpPageSize;
        int end = Math.min(entries.length, start + helpPageSize);

        Log.info("[@] yzf 命令列表 [@/@]", MindustryYZF.name, page, pages);
        for(int i = start; i < end; i++){
            HelpEntry entry = entries[i];
            Log.info("  @ - @", entry.usage, entry.description);
        }
        if(page < pages){
            Log.info("  更多内容请使用: yzf help @", page + 1);
        }
    }

    private static HelpEntry[] helpEntries(){
        return new HelpEntry[]{
            new HelpEntry("help", "yzf help [all|页码]", "显示帮助信息。"),
            new HelpEntry("status", "yzf status", "查看服务端运行状态、目录、模块和服务摘要。"),
            new HelpEntry("health", "yzf health", "查看健康摘要、依赖问题和最近失败信息。"),
            new HelpEntry("metrics", "yzf metrics", "查看运行指标、调用计数和最近一次故障。"),
            new HelpEntry("scan", "yzf scan", "重新扫描模块目录。"),
            new HelpEntry("watch", "yzf watch on|off|restart|status", "控制文件热监听。"),
            new HelpEntry("hotmods", "yzf hotmods", "Hot reload YZF modules and Mindustry script mods."),
            new HelpEntry("reload", "yzf reload [author/moduleId]", "重载全部模块，或只重载指定模块。"),
            new HelpEntry("modules", "yzf modules", "列出全部模块。"),
            new HelpEntry("info", "yzf info <author/moduleId>", "查看模块详情。"),
            new HelpEntry("enable", "yzf enable <author/moduleId>", "启用模块。"),
            new HelpEntry("disable", "yzf disable <author/moduleId>", "禁用模块。"),
            new HelpEntry("plugins", "yzf plugins", "列出所有插件（plugins/ 目录）。"),
            new HelpEntry("plugin", "yzf plugin enable|disable <id>", "启用或禁用插件。"),
            new HelpEntry("commands", "yzf commands", "列出所有已注册命令。"),
            new HelpEntry("mod", "yzf mod <moduleId> | register | unregister | list", "查看模块详情及命令管理。"),
            new HelpEntry("services", "yzf services", "列出服务与健康状态。"),
            new HelpEntry("service", "yzf service reload|info|ping|sqltest|redistest|httptest|miniotest", "执行服务相关管理和测试操作。"),
            new HelpEntry("permissions", "yzf permissions reload|check <uuid|comid> <permission>|roles", "重载权限、检查 UUID/comid 权限或列出角色。"),
            new HelpEntry("runtime", "yzf runtime", "查看运行时桥状态与已加载模块。"),
            new HelpEntry("audit", "yzf audit [tail [N]]", "查看审计日志尾部。"),
            new HelpEntry("verify", "yzf verify", "输出当前可运行验证摘要。"),
            new HelpEntry("api", "yzf api [summary|list|info <id>|manifest|readOnly|writeOnly]", "查看公开能力清单和详细说明。"),
            new HelpEntry("players", "yzf players [页码]", "查看在线玩家详细列表，默认每页 15 人。"),
            new HelpEntry("dbs", "yzf dbs", "列出当前可查询的数据库。"),
            new HelpEntry("database", "yzf <数据库别名> [页码]", "分页查看数据库中的玩家信息。"),
            new HelpEntry("uuid", "yzf uuid <数据库别名> [页码]", "分页查看数据库中的玩家信息，并额外显示原生 UUID。")
        };
    }

    private static void printOpenApiHelp(String pageArg){
        Log.info("[@] yzf api 命令", MindustryYZF.name);
        Log.info("  yzf api summary    - 查看摘要");
        Log.info("  yzf api list       - 查看全部能力列表");
        Log.info("  yzf api info <id>  - 查看单个能力组详情");
        Log.info("  yzf api manifest   - 查看完整元数据清单");
        Log.info("  yzf api readOnly   - 查看只读能力组");
        Log.info("  yzf api writeOnly  - 查看只写能力组");
        if(pageArg != null){
            Log.info("  用法关键字: @", pageArg);
        }
    }

    private static void handleOpenApiCommand(YZFContext context, String[] args){
        if(args.length == 0){
            printOpenApiHelp(null);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch(action){
            case "summary", "概览" -> printOpenApiSummary();
            case "list", "列表" -> printOpenApiList();
            case "info", "详情" -> {
                if(args.length >= 2) printOpenApiInfo(args[1]);
                else printOpenApiHelp("info");
            }
            case "manifest", "清单" -> printOpenApiManifest();
            case "readonly", "只读" -> printOpenApiReadOnly();
            case "writeonly", "写入" -> printOpenApiWriteOnly();
            case "help", "帮助" -> printOpenApiHelp(args.length >= 2 ? args[1] : null);
            default -> Log.err("未知的 yzf api 子命令 '@'。请使用 `yzf api help` 查看说明。", action);
        }
    }

    private static void printOpenApiSummary(){
        Log.info("[@] 公开能力摘要", MindustryYZF.name);
        Log.info("  @", YZFOpenApiRegistry.summaryJson());
    }

    private static void printOpenApiList(){
        Log.info("[@] 公开能力列表", MindustryYZF.name);
        Log.info("  @", YZFOpenApiRegistry.listJson());
    }

    private static void printOpenApiInfo(String capabilityId){
        Log.info("[@] 公开能力详情: @", MindustryYZF.name, capabilityId);
        Log.info("  @", YZFOpenApiRegistry.infoJson(capabilityId));
    }

    private static void printOpenApiManifest(){
        Log.info("[@] 公开能力完整清单", MindustryYZF.name);
        Log.info("  @", YZFOpenApiRegistry.manifestJson());
    }

    private static void printOpenApiReadOnly(){
        Log.info("[@] 公开能力只读列表", MindustryYZF.name);
        Log.info("  @", YZFOpenApiRegistry.readOnlyJson());
    }

    private static void printOpenApiWriteOnly(){
        Log.info("[@] 公开能力只写列表", MindustryYZF.name);
        Log.info("  @", YZFOpenApiRegistry.writeOnlyJson());
    }

    private static void printStatus(YZFContext context){
        Log.info("[@] 版本=@ 运行时=@ 文件监听=@", MindustryYZF.name, MindustryYZF.version, context.runtime.mode(), context.watcher.running() ? "开启" : "关闭");
        Log.info("  根目录: @", context.paths.root.absolutePath());
        Log.info("  模块目录: @", context.paths.modulesDir.absolutePath());
        Log.info("  日志目录: @", context.paths.logsDir.absolutePath());
        Log.info("  配置目录: @", context.paths.configDir.absolutePath());
        Log.info("  服务目录: @", context.paths.servicesDir.absolutePath());
        Log.info("  远程目录: @", context.paths.remotesDir.absolutePath());
        Log.info("  终端配置: @", context.paths.terminalFile.absolutePath());
        Log.info("  审计日志: @", context.audit.path());
        Log.info("  已发现模块: @", context.registry.moduleCount());
        Log.info("  已发现脚本: @", context.registry.scriptCount());
        Log.info("  已加载服务: @", context.services.registry().all().size);
        if(context.runtime instanceof YZFJsRuntime runtime){
            Log.info("  已加载模块总数: @", runtime.loadedModuleCount());
            Log.info("  进程模块数: @", runtime.processModuleCount());
        }
    }

    private static void printHealth(YZFContext context){
        Log.info("[@] 健康摘要", MindustryYZF.name);
        Log.info("  模块依赖错误: @", context.registry.dependencyErrors().size);
        Log.info("  模块依赖警告: @", context.registry.dependencyWarnings().size);
        Log.info("  服务健康: @/@", context.services.registry().healthyCount(), context.services.registry().all().size);
        Log.info("  最近失败: @", YZFText.blank(context.metrics.lastFailure) ? "<无>" : context.metrics.lastFailure);
        for(String warning : context.registry.dependencyWarnings()){
            Log.warn("  警告: @", warning);
        }
        for(String error : context.registry.dependencyErrors()){
            Log.err("  错误: @", error);
        }
    }

    private static void printMetrics(YZFContext context){
        Log.info("[@] 指标", MindustryYZF.name);
        Log.info("  运行时长(ms): @", System.currentTimeMillis() - context.metrics.startedAtMillis);
        Log.info("  模块加载次数: @", context.metrics.moduleLoads.get());
        Log.info("  模块重载次数: @", context.metrics.moduleReloads.get());
        Log.info("  模块失败次数: @", context.metrics.moduleFailures.get());
        Log.info("  模块回滚次数: @", context.metrics.moduleRollbacks.get());
        Log.info("  服务加载次数: @", context.metrics.serviceLoads.get());
        Log.info("  服务失败次数: @", context.metrics.serviceFailures.get());
        Log.info("  服务调用次数: @", context.metrics.serviceCalls.get());
        Log.info("  SQL 调用次数: @", context.metrics.sqlCalls.get());
        Log.info("  Redis 调用次数: @", context.metrics.redisCalls.get());
        Log.info("  控制台命令调用次数: @", context.metrics.serverCommandCalls.get());
        Log.info("  玩家命令调用次数: @", context.metrics.playerCommandCalls.get());
        Log.info("  权限拒绝次数: @", context.metrics.permissionDenied.get());
        Log.info("  远程调用次数: @", context.metrics.remoteCalls.get());
        Log.info("  协议入站次数: @", context.metrics.protocolIn.get());
        Log.info("  协议出站次数: @", context.metrics.protocolOut.get());
        Log.info("  审计事件次数: @", context.metrics.auditEvents.get());
        Log.info("  最近失败: @", YZFText.blank(context.metrics.lastFailure) ? "<无>" : context.metrics.lastFailure);
        Log.info("  Network upload BPS: @", YZFNetworkMetrics.currentUploadBps());
        Log.info("  Network download BPS: @", YZFNetworkMetrics.currentDownloadBps());
        Log.info("  Sync client snapshots: @", YZFNetworkMetrics.syncClientSnapshots());
        Log.info("  Sync dropped snapshots: @", YZFNetworkMetrics.syncDroppedSnapshots());
        Log.info("  Sync corrections: @", YZFNetworkMetrics.syncCorrections());
        Log.info("  Sync rubberbands: @", YZFNetworkMetrics.syncRubberbands());
        Log.info("  Sync forced reliable snapshots: @", YZFNetworkMetrics.syncForcedReliableSnapshots());
        Log.info("  Sync last position error: @", YZFNetworkMetrics.syncLastPositionError());
    }

    private static void printModules(YZFContext context){
        if(context.registry.moduleCount() == 0){
            Log.info("[@] 当前没有发现任何模块。目录: @", MindustryYZF.name, context.paths.modulesDir.absolutePath());
            return;
        }

        Log.info("[@] 模块列表", MindustryYZF.name);
        for(YZFModuleDefinition module : context.registry.modules()){
            Log.info("  @", module.fullId());
            Log.info("    名称: @", module.meta.name);
            Log.info("    版本: @", module.meta.version);
            Log.info("    运行时: @", module.meta.runtime);
            Log.info("    分类: @", module.meta.category);
            Log.info("    启用: @", module.meta.enabled ? "是" : "否");
        }
    }

    private static void printModuleInfo(YZFContext context, String[] args){
        if(args.length < 2){
            Log.err("用法: yzf info <author/moduleId>");
            return;
        }

        YZFModuleDefinition module = context.registry.find(args[1]);
        if(module == null){
            Log.err("找不到模块: @", args[1]);
            return;
        }

        Log.info("[@] 模块详情 @", MindustryYZF.name, module.fullId());
        Log.info("  名称: @", module.meta.name);
        Log.info("  版本: @", module.meta.version);
        Log.info("  作者: @", module.meta.author);
        Log.info("  描述: @", module.meta.description);
        Log.info("  运行时: @", module.meta.runtime);
        Log.info("  启用: @", module.meta.enabled ? "是" : "否");
        Log.info("  隐藏: @", module.meta.hidden ? "是" : "否");
        Log.info("  需要参数: @", module.meta.requiresArgs ? "是" : "否");
        Log.info("  分类: @", module.meta.category);
        Log.info("  默认权限: @", YZFText.blank(module.meta.permission) ? "<无>" : module.meta.permission);
        Log.info("  元数据文件: @", module.metaFile.absolutePath());
        Log.info("  根目录: @", module.root.absolutePath());
        Log.info("  主脚本: @", module.mainScript.absolutePath());
        Log.info("  数据目录: @", module.dataDir.absolutePath());
        Log.info("  缓存目录: @", module.cacheDir.absolutePath());
        Log.info("  硬依赖: @", module.meta.depends);
        Log.info("  软依赖: @", module.meta.softDepends);
        Log.info("  脚本数量: @", module.scripts.size);
    }

    private static void handleWatch(YZFContext context, String[] args){
        if(args.length == 1){
            Log.info("[@] 文件监听当前为 @。", MindustryYZF.name, context.watcher.running() ? "开启" : "关闭");
            return;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        switch(mode){
            case "status", "状态" -> Log.info("[@] 文件监听当前为 @。", MindustryYZF.name, context.watcher.running() ? "开启" : "关闭");
            case "on", "开", "开启" -> {
                if(!context.watcher.start()){
                    Log.info("[@] 文件监听已经处于开启状态。", MindustryYZF.name);
                }
            }
            case "off", "关", "关闭" -> {
                if(!context.watcher.stop()){
                    Log.info("[@] 文件监听已经处于关闭状态。", MindustryYZF.name);
                }
            }
            case "restart", "重启" -> context.watcher.restart();
            default -> Log.err("未知的监听参数 '@'。可用值: on | off | restart | status", mode);
        }
    }

    private static void toggleModule(YZFContext context, String[] args, boolean enabled){
        if(args.length < 2){
            Log.err("用法: yzf @ <author/moduleId>", enabled ? "enable" : "disable");
            return;
        }

        YZFModuleDefinition module = context.registry.find(args[1]);
        if(module == null){
            Log.err("找不到模块: @", args[1]);
            return;
        }

        module.meta.enabled = enabled;
        YZFModuleIO.writeMeta(module);
        context.registry.scan();
        if(enabled) context.runtime.reloadModule(module.fullId());
        else context.runtime.reloadAll();

        context.audit.record("module-toggle", module.fullId(), enabled ? "enable" : "disable");
        Log.info("[@] 模块 '@' 已设置为 @。", MindustryYZF.name, module.fullId(), enabled ? "启用" : "禁用");
    }

    private static void printPlugins(YZFContext context){
        Seq<YZFModuleDefinition> plugins = new Seq<>();
        for(YZFModuleDefinition m : context.registry.modules()){
            if("plugins".equals(m.meta._source)){
                plugins.add(m);
            }
        }
        if(plugins.isEmpty()){
            Log.info("[@] 当前没有发现任何插件。目录: @", MindustryYZF.name, context.paths.pluginsDir.absolutePath());
            return;
        }
        Log.info("[@] 插件列表 (@)", MindustryYZF.name, plugins.size);
        for(YZFModuleDefinition m : plugins){
            Log.info("  @ (@) v@ - @", m.fullId(), m.meta.name, m.meta.version, m.meta.enabled ? "已启用" : "已禁用");
        }
    }

    private static void handlePlugin(YZFContext context, String[] args){
        if(args.length < 3){
            Log.err("用法: yzf plugin enable <id>");
            Log.err("      yzf plugin disable <id>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean enabled;
        switch(action){
            case "enable", "启用" -> enabled = true;
            case "disable", "禁用" -> enabled = false;
            default -> {
                Log.err("未知操作: @。可用值: enable | disable", action);
                return;
            }
        }
        toggleModule(context, new String[]{args[0], args[2]}, enabled);
    }

    private static void printServices(YZFContext context){
        if(context.services.registry().all().isEmpty()){
            Log.info("[@] 当前没有已加载服务。目录: @", MindustryYZF.name, context.paths.servicesDir.absolutePath());
            return;
        }

        Log.info("[@] 服务列表", MindustryYZF.name);
        for(YZFServiceClient service : context.services.registry().all()){
            Log.info("  @", service.config().id);
            Log.info("    类型: @", service.config().type);
            Log.info("    集群模式: @", service.config().clusterMode);
            Log.info("    摘要: @", service.summary());
            Log.info("    健康状态: @", service.healthy() ? "正常" : "异常");
            Log.info("    健康详情: @", service.healthDetails());
            Log.info("    配置文件: @", service.config().sourcePath);
        }
    }

    private static void handleServiceAction(YZFContext context, String[] args){
        if(args.length < 2){
            Log.err("用法: yzf service reload");
            Log.err("      yzf service info <serviceId>");
            Log.err("      yzf service ping <serviceId>");
            Log.err("      yzf service sqltest <serviceId> <sql>");
            Log.err("      yzf service redistest <serviceId> <key>");
            Log.err("      yzf service httptest <serviceId> <path>");
            Log.err("      yzf service miniotest <serviceId> <objectName>");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if(action.equals("reload") || action.equals("重载")){
            context.services.reload();
            context.databaseRegistry.attachServiceRegistry(context.services.registry());
            context.audit.record("service-reload", "all", null);
            Log.info("[@] 服务配置已重载。", MindustryYZF.name);
            return;
        }

        if((action.equals("info") || action.equals("详情")) && args.length >= 3){
            YZFServiceClient client = context.services.registry().get(args[2]);
            YZFServiceConfig config = context.services.findConfig(args[2]);
            if(config == null){
                Log.err("找不到服务配置: @", args[2]);
                return;
            }

            Log.info("[@] 服务详情 @", MindustryYZF.name, config.id);
            Log.info("  类型: @", config.type);
            Log.info("  启用: @", config.enabled ? "是" : "否");
            Log.info("  集群模式: @", config.clusterMode);
            Log.info("  端点: @", config.endpoint);
            Log.info("  节点数: @", config.nodes.size);
            Log.info("  数据库: @", YZFText.blank(config.database) ? "<无>" : config.database);
            Log.info("  数据库文件: @", YZFText.blank(config.databaseFile) ? "<无>" : config.databaseFile);
            Log.info("  用户名: @", YZFText.blank(config.username) ? "<无>" : config.username);
            Log.info("  密码: @", YZFText.blank(config.password) ? "<无>" : YZFSecurity.mask(config.password));
            Log.info("  配置文件: @", config.sourcePath);
            Log.info("  健康状态: @", client != null && client.healthy() ? "正常" : "未连接/异常");
            if(client != null) Log.info("  健康详情: @", client.healthDetails());
            return;
        }

        if((action.equals("ping") || action.equals("test") || action.equals("测试")) && args.length >= 3){
            YZFServiceClient client = context.services.registry().get(args[2]);
            if(client == null){
                Log.err("找不到已加载服务: @", args[2]);
                return;
            }
            Log.info("[@] 服务 '@' 健康状态: @", MindustryYZF.name, args[2], client.healthy() ? "正常" : "异常");
            Log.info("  详情: @", client.healthDetails());
            return;
        }

        if(action.equals("sqltest") && args.length >= 4){
            try{
                String sql = join(args, 3);
                YZFScriptServices services = new YZFScriptServices(context);
                if(sql.trim().toLowerCase(Locale.ROOT).startsWith("select")){
                    Log.info("[@] SQL 测试结果: @", MindustryYZF.name, services.sqlQueryJson(args[2], sql));
                }else{
                    Log.info("[@] SQL 影响行数: @", MindustryYZF.name, services.sqlExecute(args[2], sql));
                }
            }catch(Exception e){
                Log.err("[@] SQL 测试失败。", MindustryYZF.name, e);
            }
            return;
        }

        if(action.equals("redistest") && args.length >= 4){
            try{
                YZFScriptServices services = new YZFScriptServices(context);
                String key = args[3];
                services.redisSet(args[2], key, "yzf-ok");
                long counter = services.redisIncrement(args[2], key + ":counter");
                services.redisHashSet(args[2], key + ":hash", "value", "yzf-hash-ok");
                Log.info("[@] Redis get=@ incr=@ hget=@", MindustryYZF.name,
                    services.redisGet(args[2], key),
                    counter,
                    services.redisHashGet(args[2], key + ":hash", "value"));
            }catch(Exception e){
                Log.err("[@] Redis 测试失败。", MindustryYZF.name, e);
            }
            return;
        }

        if(action.equals("httptest") && args.length >= 4){
            try{
                Log.info("[@] HTTP 测试结果: @", MindustryYZF.name, new YZFScriptServices(context).httpGet(args[2], args[3]));
            }catch(Exception e){
                Log.err("[@] HTTP 测试失败。", MindustryYZF.name, e);
            }
            return;
        }

        if(action.equals("miniotest") && args.length >= 4){
            try{
                new YZFScriptServices(context).minioPutText(args[2], args[3], "yzf-minio-ok");
                Log.info("[@] MinIO 测试写入完成。", MindustryYZF.name);
            }catch(Exception e){
                Log.err("[@] MinIO 测试失败。", MindustryYZF.name, e);
            }
            return;
        }

        Log.err("未知的服务子命令。请使用 `yzf help` 查看说明。");
    }

    private static void handlePermissions(YZFContext context, String[] args){
        if(args.length < 2){
            Log.err("用法: yzf permissions reload");
            Log.err("      yzf permissions roles");
            Log.err("      yzf permissions check <uuid|comid> <permission>");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if(action.equals("reload") || action.equals("重载")){
            context.permissions.reload();
            Log.info("[@] 权限配置已重载。", MindustryYZF.name);
            return;
        }
        if(action.equals("roles") || action.equals("角色")){
            Log.info("[@] 权限角色: @", MindustryYZF.name, context.permissions.roles());
            return;
        }
        if((action.equals("check") || action.equals("检查")) && args.length >= 4){
            String subject = resolvePermissionSubject(context, args[2]);
            boolean allowed = context.permissions.has(subject, false, args[3]);
            Log.info("[@] Subject=@ (input=@) permission=@ -> @", MindustryYZF.name, subject, args[2], args[3], allowed ? "allow" : "deny");
            return;
        }
        Log.err("未知的权限子命令 '@'。", action);
    }

    private static String resolvePermissionSubject(YZFContext context, String input){
        if(input == null) return null;
        String trimmed = input.trim();
        if(trimmed.isEmpty()) return trimmed;

        try{
            long comid = Long.parseLong(trimmed);
            String uuid = context.comidRegistry.getUuid(comid);
            if(uuid != null && !uuid.isBlank()){
                return uuid;
            }
        }catch(NumberFormatException ignored){
        }
        return trimmed;
    }

    private static void printRuntime(YZFContext context){
        Log.info("[@] 运行时状态", MindustryYZF.name);
        Log.info("  模式: @", context.runtime.mode());
        Log.info("  支持运行时: @", YZFModuleLoader.supportedRuntimes());
        Log.info("  支持脚本扩展: @", YZFModuleLoader.supportedScriptExtensions());
        if(context.runtime instanceof YZFJsRuntime runtime){
            Log.info("  已加载模块: @", runtime.loadedModuleIds());
            Log.info("  已加载模块总数: @", runtime.loadedModuleCount());
            Log.info("  进程模块数: @", runtime.processModuleCount());
        }
    }

    private static void printAudit(YZFContext context, String[] args){
        int lines = 20;
        if(args.length >= 3 && args[1].equalsIgnoreCase("tail")){
            lines = Integer.parseInt(args[2]);
        }else if(args.length >= 2 && !args[1].equalsIgnoreCase("tail")){
            lines = Integer.parseInt(args[1]);
        }

        Log.info("[@] 审计日志尾部 (@ 行)", MindustryYZF.name, lines);
        for(String line : context.audit.tail(lines)){
            Log.info("  @", line);
        }
    }

    private static void printVerify(YZFContext context){
        Log.info("[@] 可运行验证摘要", MindustryYZF.name);
        printStatus(context);
        printHealth(context);
        printRuntime(context);
    }

    private static void printCommands(YZFContext context){
        Log.info("[@] 已注册命令列表", MindustryYZF.name);

        if(context.runtime instanceof YZFJsRuntime runtime){
            ObjectMap<String, String> cmdOwners = runtime.commandOwners();
            if(!cmdOwners.isEmpty()){
                Log.info("  [控制台命令]");
                for(var entry : cmdOwners){
                    Log.info("    @ - 模块: @", entry.key, entry.value);
                }
            }else{
                Log.info("  [控制台命令] 无");
            }

            ObjectMap<String, String> pCmdOwners = runtime.playerCommandOwners();
            if(!pCmdOwners.isEmpty()){
                Log.info("  [玩家命令]");
                for(var entry : pCmdOwners){
                    Log.info("    @ - 模块: @", entry.key, entry.value);
                }
            }else{
                Log.info("  [玩家命令] 无");
            }
        }

        ObjectMap<String, YZFCommandRegistry.RegisteredCommand> registered = context.commandRegistry.all();
        if(!registered.isEmpty()){
            Log.info("  [可调用命令]");
            for(var entry : registered){
                YZFCommandRegistry.RegisteredCommand cmd = entry.value;
                Log.info("    @ - 模块: @ @", cmd.name, cmd.moduleId, cmd.description.isEmpty() ? "" : cmd.description);
            }
        }else{
            Log.info("  [可调用命令] 无");
        }
    }

    private static void handleMod(YZFContext context, String[] args){
        if(args.length < 2){
            printModHelp();
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch(sub){
            case "register", "注册" -> handleModRegister(context, args);
            case "unregister", "注销" -> handleModUnregister(context, args);
            case "list", "列表" -> handleModList(context, args);
            case "help", "帮助" -> printModHelp();
            default -> handleModInfo(context, args);
        }
    }

    private static void printModHelp(){
        Log.info("[@] yzf mod 子命令列表", MindustryYZF.name);
        Log.info("  yzf mod <moduleId> - 查看模块详情及其注册的命令");
        Log.info("  yzf mod register <moduleId> server <name> <description> - 注册控制台命令");
        Log.info("  yzf mod register <moduleId> player <name> <description> - 注册玩家命令");
        Log.info("  yzf mod unregister <moduleId> <name> - 注销命令");
        Log.info("  yzf mod list <moduleId> - 列出模块所有命令");
    }

    private static void handleModRegister(YZFContext context, String[] args){
        if(args.length < 6){
            Log.err("用法: yzf mod register <moduleId> server|player <name> <description>");
            return;
        }

        YZFModuleDefinition module = findModule(context, args[2]);
        if(module == null) return;

        String type = args[3].toLowerCase(Locale.ROOT);
        String name = args[4];
        String description = join(args, 5);

        if(!YZFSecurity.validCommandName(name)){
            Log.err("非法命令名: @", name);
            return;
        }

        try{
            if(type.equals("server") || type.equals("控制台")){
                context.modCommands.registerServerCommand(module, name, "", description, null);
                Log.info("[@] 已为模块 '@' 注册控制台命令 '@'。", MindustryYZF.name, module.fullId(), name);
            }else if(type.equals("player") || type.equals("玩家")){
                context.modCommands.registerPlayerCommand(module, name, "", description, false, module.meta.permission, null);
                Log.info("[@] 已为模块 '@' 注册玩家命令 '@'。", MindustryYZF.name, module.fullId(), name);
            }else{
                Log.err("未知命令类型: @。可用值: server | player", type);
            }
        }catch(Exception e){
            Log.err("注册命令失败: @", e.getMessage());
        }
    }

    private static void handleModUnregister(YZFContext context, String[] args){
        if(args.length < 4){
            Log.err("用法: yzf mod unregister <moduleId> <name>");
            return;
        }

        YZFModuleDefinition module = findModule(context, args[2]);
        if(module == null) return;

        String name = args[3];
        boolean removed = context.modCommands.unregisterCommand(module.fullId(), name);
        if(removed){
            Log.info("[@] 已注销模块 '@' 的命令 '@'。", MindustryYZF.name, module.fullId(), name);
        }else{
            Log.err("找不到命令 '@' (模块: @)", name, module.fullId());
        }
    }

    private static void handleModList(YZFContext context, String[] args){
        if(args.length < 3){
            Log.err("用法: yzf mod list <moduleId>");
            return;
        }

        YZFModuleDefinition module = findModule(context, args[2]);
        if(module == null) return;

        String moduleId = module.fullId();
        String json = context.modCommands.listCommands(moduleId);
        Log.info("[@] 模块 '@' 注册的命令", MindustryYZF.name, moduleId);
        Log.info("  @", json);
    }

    private static void handleModInfo(YZFContext context, String[] args){
        YZFModuleDefinition module = findModule(context, args[1]);
        if(module == null) return;

        String moduleId = module.fullId();
        boolean isPlugin = "plugins".equals(module.meta._source);

        Log.info("[@] @ 详情: @", MindustryYZF.name, isPlugin ? "插件" : "模块", moduleId);
        Log.info("  名称: @", module.meta.name);
        Log.info("  版本: @", module.meta.version);
        Log.info("  作者: @", module.meta.author);
        Log.info("  描述: @", module.meta.description);
        Log.info("  运行时: @", module.meta.runtime);
        Log.info("  启用: @", module.meta.enabled ? "是" : "否");
        Log.info("  分类: @", module.meta.category);
        Log.info("  来源: @", isPlugin ? "plugins/" : "modules/");
        Log.info("  根目录: @", module.root.absolutePath());

        if(context.runtime instanceof YZFJsRuntime runtime){
            ObjectMap<String, String> cmdOwners = runtime.commandOwners();
            Seq<String> serverCmds = new Seq<>();
            for(var entry : cmdOwners){
                if(moduleId.equals(entry.value)) serverCmds.add(entry.key);
            }

            ObjectMap<String, String> pCmdOwners = runtime.playerCommandOwners();
            Seq<String> playerCmds = new Seq<>();
            for(var entry : pCmdOwners){
                if(moduleId.equals(entry.value)) playerCmds.add(entry.key);
            }

            String callableJson = context.commandRegistry.listModuleAsJson(moduleId);
            if(!serverCmds.isEmpty()) Log.info("  [控制台命令] @", serverCmds);
            if(!playerCmds.isEmpty()) Log.info("  [玩家命令] @", playerCmds);
            if(!"[]".equals(callableJson)) Log.info("  [可调用命令] @", callableJson);
            if(serverCmds.isEmpty() && playerCmds.isEmpty() && "[]".equals(callableJson)){
                Log.info("  [命令] 无");
            }
        }
    }

    private static YZFModuleDefinition findModule(YZFContext context, String idOrName){
        YZFModuleDefinition module = context.registry.find(idOrName);
        if(module == null){
            for(YZFModuleDefinition m : context.registry.modules()){
                if(m.fullId().contains(idOrName) || m.id().contains(idOrName)){
                    module = m;
                    break;
                }
            }
        }
        if(module == null){
            Log.err("找不到模块: @", idOrName);
        }
        return module;
    }

    private static void printDetailedPlayers(YZFContext context, String[] args){
        int page = parsePageArg(args.length >= 2 ? args[1] : null);
        Seq<Player> players = Groups.player.copy();
        players.sort((a, b) -> safe(a.name()).compareToIgnoreCase(safe(b.name())));

        int total = players.size;
        int pages = Math.max(1, (total + playerPageSize - 1) / playerPageSize);
        if(page > pages) page = pages;
        int start = Math.max(0, (page - 1) * playerPageSize);
        int end = Math.min(total, start + playerPageSize);

        Log.info("[@] 在线玩家详细列表 [@/@] 总数=@", MindustryYZF.name, page, pages, total);
        if(total == 0){
            Log.info("  当前没有在线玩家。");
            return;
        }

        for(int i = start; i < end; i++){
            Player player = players.get(i);
            long comid = context.comidRegistry.getComid(player.uuid());
            YZFPlayerSqlStore.PlayerProfile profile = context.playerDataStore != null && context.playerDataStore.sqlStore() != null
                ? context.playerDataStore.sqlStore().findPlayerProfile(player.uuid())
                : null;

            long assignedAt = profile != null ? profile.comidAssignedAt : 0L;
            long registerAt = profile != null ? profile.firstSeenAt : 0L;
            long bindAt = profile != null ? profile.lastBoundAt : 0L;
            Log.info("  [@] 名称=@ comid=@ ip=@", i + 1, renderName(player.name()), comid > 0L ? String.valueOf(comid) : "<未分配>", safe(player.ip()));
            Log.info("      首次分配comid时间=@ 注册时间=@ 绑定时间=@", formatTime(assignedAt), formatTime(registerAt), formatTime(bindAt));
        }
    }

    private static void printDatabases(YZFContext context){
        Log.info("[@] 数据库列表", MindustryYZF.name);
        Jval parsed = Jval.read(context.databaseRegistry.listJson());
        if(parsed == null || !parsed.isArray() || parsed.asArray().isEmpty()){
            Log.info("  当前没有可用数据库。");
            return;
        }
        Seq<DatabaseAlias> aliases = buildDatabaseAliases(context);
        for(DatabaseAlias alias : aliases){
            Log.info("  @ -> 数据库ID=@ 类型=@", alias.alias, alias.databaseId, alias.typeLabel);
        }
        Log.info("  用法: yzf <数据库别名> [页码]");
        Log.info("  用法: yzf uuid <数据库别名> [页码]");
    }

    private static void printDatabasePlayers(YZFContext context, String databaseId, String[] args, boolean includeUuid){
        YZFSqlClient sqlClient = resolveSqlDatabase(context, databaseId);
        if(sqlClient == null){
            Log.err("数据库 '@' 当前不是可查询的 SQL 玩家数据库。", databaseId);
            return;
        }

        YZFPlayerSqlStore store = new YZFPlayerSqlStore(sqlClient);
        store.ensureSchema();
        int page = parsePageArg(args.length >= 2 ? args[1] : null);
        YZFPlayerSqlStore.PlayerDirectoryPage result = store.listPlayerProfiles(page, playerPageSize, includeUuid);

        Log.info("[@] 数据库玩家列表 @ [@/@] 总数=@", MindustryYZF.name, databaseId, result.page, result.totalPages(), result.total);
        if(result.records.isEmpty()){
            Log.info("  当前数据库没有玩家记录。");
            return;
        }
        for(int i = 0; i < result.records.size; i++){
            YZFPlayerSqlStore.PlayerProfile record = result.records.get(i);
            Log.info("  [@] 名称=@ comid=@ ip=@", ((result.page - 1) * result.pageSize) + i + 1, renderName(record.lastName), record.comid, safe(record.lastIp));
            if(includeUuid){
                Log.info("      uuid=@ 注册时间=@ 绑定时间=@", safe(record.uuid), formatTime(record.firstSeenAt), formatTime(record.lastBoundAt));
            }else{
                Log.info("      注册时间=@ 绑定时间=@", formatTime(record.firstSeenAt), formatTime(record.lastBoundAt));
            }
        }
    }

    private static void printDatabasePlayersWithUuid(YZFContext context, String[] args){
        DatabaseQuery query = parseDatabaseQuery(args, 1);
        if(query == null || YZFText.blank(query.alias)){
            Log.err("用法: yzf uuid <数据库别名> [页码]");
            return;
        }

        String databaseId = resolveDatabaseAlias(context, query.alias);
        if(databaseId == null){
            Log.err("找不到数据库别名 '@'。请先使用 `yzf dbs` 查看。", query.alias);
            return;
        }
        String[] forwarded = new String[]{databaseId, String.valueOf(query.page)};
        printDatabasePlayers(context, databaseId, forwarded, true);
    }

    private static YZFSqlClient resolveSqlDatabase(YZFContext context, String databaseId){
        if(YZFText.blank(databaseId)) return null;
        YZFServiceClient direct = context.services.registry().get(databaseId);
        if(direct instanceof YZFSqlClient sqlDirect){
            return sqlDirect;
        }
        if(databaseId.startsWith("service-")){
            String serviceId = databaseId.substring("service-".length());
            return context.services.registry().getAs(serviceId, YZFSqlClient.class);
        }
        return context.services.registry().getAs(databaseId, YZFSqlClient.class);
    }

    private static String resolveDatabaseAlias(YZFContext context, String raw){
        if(YZFText.blank(raw)) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for(DatabaseAlias alias : buildDatabaseAliases(context)){
            if(alias.alias.equalsIgnoreCase(normalized) || alias.databaseId.equalsIgnoreCase(normalized)){
                return alias.databaseId;
            }
        }
        return context.databaseRegistry.has(normalized) ? normalized : null;
    }

    private static DatabaseQuery parseDatabaseQuery(String[] args, int startIndex){
        if(args.length <= startIndex) return null;
        String joined = join(args, startIndex).trim();
        if(joined.isEmpty()) return null;

        String[] pieces = joined.split("\\s+");
        DatabaseQuery query = new DatabaseQuery();
        query.alias = pieces[0];
        query.page = 1;
        if(pieces.length >= 2){
            query.page = parsePageArg(pieces[1]);
        }
        return query;
    }

    private static Seq<DatabaseAlias> buildDatabaseAliases(YZFContext context){
        Seq<DatabaseAlias> aliases = new Seq<>();
        Jval parsed = Jval.read(context.databaseRegistry.listJson());
        if(parsed == null || !parsed.isArray()) return aliases;

        int index = 1;
        for(Jval item : parsed.asArray()){
            String id = item.getString("id", "").trim();
            if(YZFText.blank(id)) continue;
            String type = item.getString("type", "unknown");
            aliases.add(new DatabaseAlias("db" + index, id, type));
            index++;
        }
        return aliases;
    }

    private static String[] slice(String[] args, int start){
        if(args.length <= start) return new String[0];
        String[] result = new String[args.length - start];
        System.arraycopy(args, start, result, 0, result.length);
        return result;
    }

    private static String join(String[] values, int start){
        StringBuilder builder = new StringBuilder();
        for(int i = start; i < values.length; i++){
            if(i > start) builder.append(' ');
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static int parsePageArg(String raw){
        if(YZFText.blank(raw)) return 1;
        try{
            return Math.max(1, Integer.parseInt(raw.trim()));
        }catch(Exception ignored){
            return 1;
        }
    }

    private static String formatTime(long millis){
        if(millis <= 0L) return "<未知>";
        synchronized(timeFormat){
            return timeFormat.format(new Date(millis));
        }
    }

    private static String safe(String value){
        return YZFText.blank(value) ? "<空>" : value;
    }

    private static String renderName(String raw){
        if(YZFText.blank(raw)) return "<空>";
        String input = raw.trim();
        Matcher matcher = hexColorPattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while(matcher.find()){
            String color = matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(mapHexToConsole(color)));
        }
        matcher.appendTail(out);
        String rendered = out.toString().replace("[]", "&fr");
        if(!rendered.endsWith("&fr")){
            rendered += "&fr";
        }
        return rendered;
    }

    private static String mapHexToConsole(String hex){
        if(hex == null || hex.length() < 6) return "&fr";
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        int avg = (r + g + b) / 3;

        if(avg <= 32) return "&lk";
        if(r >= g + 40 && r >= b + 40) return "&lr";
        if(b >= r + 30 && b >= g + 10) return "&lb";
        if(g >= r + 20 && b >= r + 20) return "&lc";
        if(avg >= 200) return "&lw";
        return "&lw";
    }

    private static void printStructuredStatus(YZFContext context){
        Jval status = Jval.read(YZFStatusUi.statusJson());
        Log.info("[@] Status", MindustryYZF.name);
        Log.info("  Version: @", status.getString("version", MindustryYZF.version));
        Log.info("  Runtime: @", status.getString("runtimeMode", "unknown"));
        Log.info("  TPS: @", status.getInt("tps", 0));
        Log.info("  Players: @", status.getInt("players", 0));
        Log.info("  Network IO: in=@ B/s out=@ B/s", status.getLong("networkDownloadBps", 0L), status.getLong("networkUploadBps", 0L));
        Log.info("  Sync: snapshots=@ dropped=@ corrections=@ rubberbands=@ forcedReliable=@ lastError=@",
        status.getLong("syncClientSnapshots", 0L),
        status.getLong("syncDroppedSnapshots", 0L),
        status.getLong("syncCorrections", 0L),
        status.getLong("syncRubberbands", 0L),
        status.getLong("syncForcedReliableSnapshots", 0L),
        status.getFloat("syncLastPositionError", 0f));
        Log.info("  Plugins: @", status.getInt("pluginCount", 0));
        Log.info("  Modules: @", status.getInt("modules", 0));
        Log.info("  Services: @", status.getInt("services", 0));
        Log.info("  Root: @", context.paths.root.absolutePath());
        Log.info("  OpenAPI: use yzf api summary / manifest for structured output");
    }

    private static final class HelpEntry{
        final String key;
        final String usage;
        final String description;

        HelpEntry(String key, String usage, String description){
            this.key = key;
            this.usage = usage;
            this.description = description;
        }
    }

    private static final class DatabaseAlias{
        final String alias;
        final String databaseId;
        final String typeLabel;

        DatabaseAlias(String alias, String databaseId, String typeLabel){
            this.alias = alias;
            this.databaseId = databaseId;
            this.typeLabel = typeLabel;
        }
    }

    private static final class DatabaseQuery{
        String alias;
        int page;
    }
}
