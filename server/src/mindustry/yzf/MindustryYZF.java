package mindustry.yzf;

import arc.Events;
import arc.func.Cons;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.server.ServerControl;

public final class MindustryYZF{
    public static final String name = "MindustryYZF";
    public static final String version = "0.2.0-alpha";

    private static volatile boolean bootstrapped;
    private static boolean shutdownHookInstalled;
    private static volatile boolean shuttingDown;
    // Accessed by HTTP/WebSocket/plugin executor threads after bootstrap completes.
    private static volatile YZFContext context;
    private static volatile YZFExternalAccessConfig externalAccess;
    private static volatile YZFModHotReloadManager modHotReloadManager;
    private static Cons<ResetEvent> resetHandler;
    private static Cons<PlayerJoin> playerJoinHandler;

    private MindustryYZF(){
    }

    public static synchronized void bootstrap(String[] args, ServerControl serverControl){
        if(bootstrapped) return;
        shuttingDown = false;
        if(!shutdownHookInstalled){
            Runtime.getRuntime().addShutdownHook(new Thread(MindustryYZF::shutdown, "MindustryYZF-Shutdown"));
            shutdownHookInstalled = true;
        }

        YZFPaths paths = YZFPaths.create(Vars.dataDirectory.child("yzf"));
        paths.ensureLayout();
        installDefaults(paths);
        externalAccess = YZFExternalAccessConfig.load(paths);

        YZFModuleRegistry registry = new YZFModuleRegistry(paths.modulesDir, paths.pluginsDir, paths.scriptsDir);
        registry.scan();

        YZFScriptRuntime runtime = new YZFJsRuntime(registry);
        YZFFileWatcher watcher = new YZFFileWatcher(paths, registry, runtime);
        YZFDriverRegistry driverRegistry = new YZFDriverRegistry(paths);
        YZFServiceManager services = new YZFServiceManager(paths, driverRegistry);
        YZFPermissionManager permissions = new YZFPermissionManager(paths);
        YZFMetrics metrics = new YZFMetrics();
        YZFSecurityConfig securityConfig = YZFSecurityConfig.load(paths);
        YZFAuditLog audit = new YZFAuditLog(paths.auditFile, securityConfig.auditEnabled);
        YZFDatabaseRegistry databaseRegistry = new YZFDatabaseRegistry(paths);
        YZFWebSocketManager wsManager = new YZFWebSocketManager();
        YZFContentRegistry contentRegistry = new YZFContentRegistry(paths.root.child("data").file());
        YZFCommandRegistry commandRegistry = new YZFCommandRegistry();
        YZFWebUiRegistry webUi = new YZFWebUiRegistry();
        YZFModCommandInterface modCommands = new YZFModCommandInterface((YZFJsRuntime)runtime, commandRegistry, audit);

        YZFMemoryRegionManager memoryRegions = new YZFMemoryRegionManager(paths);
        context = new YZFContext(serverControl, paths, registry, runtime, watcher, services, permissions, metrics, audit, null, null, databaseRegistry, wsManager, contentRegistry, commandRegistry, webUi, modCommands, securityConfig, args, memoryRegions);
        permissions.reload();
        services.reload();
        YZFPlayerSqlStore playerSqlStore = resolvePlayerSqlStore(paths, services);
        YZFComIdRegistry comidRegistry = new YZFComIdRegistry(paths, databaseRegistry, playerSqlStore);
        YZFPlayerDataStore playerDataStore = new YZFPlayerDataStore(paths.root.child("data").file(), comidRegistry, playerSqlStore);

        context = new YZFContext(serverControl, paths, registry, runtime, watcher, services, permissions, metrics, audit, comidRegistry, playerDataStore, databaseRegistry, wsManager, contentRegistry, commandRegistry, webUi, modCommands, securityConfig, args, memoryRegions);
        YZFErrorLog.configure(paths, context.runtimeConfig.errorLoggingEnabled, context.runtimeConfig.errorTerminalColors);
        YZFServerCommands.register(context);
        databaseRegistry.attachServiceRegistry(services.registry());
        if(context.runtimeConfig.fileWatcherEnabled){
            watcher.start();
            modHotReloadManager = new YZFModHotReloadManager(context);
            modHotReloadManager.start();
        }
        runtime.reloadAll();
        resetHandler = event -> registry.scan();
        playerJoinHandler = event -> ensurePlayerComid(event.player);
        Events.on(ResetEvent.class, resetHandler);
        Events.on(PlayerJoin.class, playerJoinHandler);

        bootstrapped = true;
        audit.record("boot", name, "runtime=" + runtime.mode());

        Log.info("[@] 启动完成。运行时=@ 模块数=@ 脚本数=@ 根目录=@",
            name,
            runtime.mode(),
            registry.moduleCount(),
            registry.scriptCount(),
            paths.root.absolutePath()
        );
    }

    public static YZFContext context(){
        return context;
    }

    public static YZFExternalAccessConfig externalAccess(){ return externalAccess; }

    public static synchronized void reloadExternalAccess(){
        if(context == null) return;
        externalAccess = YZFExternalAccessConfig.load(context.paths);
        Log.info("[@] External access policy reloaded.", name);
    }

    public static boolean modHotReloadRunning(){
        return modHotReloadManager != null && modHotReloadManager.running();
    }

    public static boolean isShuttingDown(){
        return shuttingDown;
    }

    public static void reloadExternalMods(){
        if(modHotReloadManager == null){
            Log.warn("[@] Mod hot reload manager is not available.", name);
            return;
        }
        modHotReloadManager.reloadNow();
    }

    public static synchronized void shutdown(){
        if(shuttingDown) return;
        shuttingDown = true;

        try{
            if(context != null){
                context.audit.record("shutdown", name, "server stopping");
            }
        }catch(Throwable error){
            YZFErrorLog.high(name, "Failed to write shutdown audit record", error);
        }

        try{
            if(modHotReloadManager != null){
                modHotReloadManager.stop();
            }
        }catch(Throwable error){
            YZFErrorLog.high(name, "Failed to stop Mindustry mod hot reload manager", error);
        }

        if(context != null){
            try{
                if(resetHandler != null){
                    Events.remove(ResetEvent.class, resetHandler);
                    resetHandler = null;
                }
                if(playerJoinHandler != null){
                    Events.remove(PlayerJoin.class, playerJoinHandler);
                    playerJoinHandler = null;
                }
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to remove YZF event handlers", error);
            }
            try{
                context.watcher.stop();
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to stop YZF file watcher", error);
            }
            try{
                context.runtime.shutdown();
            }catch(Throwable error){
                YZFErrorLog.emergency(name, "Failed to stop YZF runtime cleanly", error);
            }
            try{
                context.wsManager.closeAll();
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to close YZF WebSocket manager", error);
            }
            try{
                context.databaseRegistry.shutdown();
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to shut down database registry", error);
            }
            try{
                context.services.shutdown();
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to shut down services", error);
            }
            try{
                context.memoryRegions.shutdown();
            }catch(Throwable error){
                YZFErrorLog.high(name, "Failed to shut down memory regions", error);
            }
        }

        modHotReloadManager = null;
        context = null;
        bootstrapped = false;
    }

    private static void installDefaults(YZFPaths paths){
        if(!paths.permissionsFile.exists()){
            paths.permissionsFile.writeString(
                "# YZF 权限配置。\n" +
                "# default 是所有玩家默认拥有的权限；defaultRoles 是所有玩家默认角色。\n" +
                "# roles 里可以定义角色权限，players 里可以按 UUID/玩家标识分配角色或权限。\n" +
                "default: []\n" +
                "defaultRoles: []\n" +
                "roles: {\n" +
                "  # 示例角色：拥有 yzf.player.* 下的玩家侧能力。\n" +
                "  moderator: [\"yzf.player.*\"]\n" +
                "}\n" +
                "players: {}\n"
            );
        }

        if(!paths.terminalFile.exists()){
            paths.terminalFile.writeString(
                "# YZF 终端/帮助显示配置。\n" +
                "# enabled 控制扩展终端功能是否启用；pageSize/helpPageSize 控制分页大小。\n" +
                "# fallbackOnDumbTerminal 为 true 时，在不支持高级终端能力的环境中自动降级。\n" +
                "enabled: false\n" +
                "foundationSupport: true\n" +
                "pageSize: 15\n" +
                "helpPageSize: 15\n" +
                "helpLanguage: \"zh\"\n" +
                "fallbackOnDumbTerminal: true\n"
                + "# simple is recommended for SSH, Docker, tmux and systemd.\n"
                + "# Use jline only when interactive completion is required.\n"
                + "consoleMode: \"simple\"\n"
                + "# Leave empty to use the active Windows code page or the platform default.\n"
                + "charset: \"\"\n"
            );
        }

        if(!paths.securityFile.exists()){
            paths.securityFile.writeString(
                "# YZF 安全配置。\n" +
                "# allowProcessRuntimes 控制是否允许外部进程型运行时。\n" +
                "# allowedRuntimes 是允许加载的脚本/进程运行时类型。\n" +
                "# auditEnabled 控制是否记录 YZF 审计日志。\n" +
                "# Process runtimes execute external programs. Enable only for trusted modules.\n" +
                "allowProcessRuntimes: false\n" +
                "allowedRuntimes: [\"js\", \"node\", \"java\", \"kt\", \"kts\"]\n" +
                "auditEnabled: true\n"
            );
        }

        if(!paths.externalAccessFile.exists()){
            paths.externalAccessFile.writeString(
                "# External access policy. Public means any non-private-network address.\n" +
                "# Token must contain at least 128 characters; prefer tokenFile or passwordFile.\n" +
                "# secretFile/keyFile accepts any private binary file; clients present sha512:<base64url-digest>.\n" +
                "enabled: true\n" +
                "requireTokenForPublic: true\n" +
                "requireTokenForPrivate: false\n" +
                "attachTokenToOutbound: true\n" +
                "requireTlsForPublic: true\n" +
                "# The command socket is plaintext. Keep false unless a trusted TLS proxy protects it.\n" +
                "allowInsecurePublicSocket: false\n" +
                "token: \"\"\n" +
                "tokenFile: \"\"\n" +
                "passwordFile: \"\"\n" +
                "secretFile: \"\"\n" +
                "keyFile: \"\"\n"
            );
        }

        if(!paths.syncConfigFile.exists()){
            paths.syncConfigFile.writeString(defaultSyncConfig());
        }

        installDefaultCompatibilityMiddleware(paths);

        installDefaultDriverTemplates(paths);
        installDefaultServiceTemplates(paths);
        installDefaultDatabaseTemplates(paths);
    }

    private static String defaultSyncConfig(){
        return "# YZF 同步稳定配置。\n" +
            "# 可以在服务器运行时编辑；同步层会定期重新读取，不需要重编译。\n" +
            "# syncReliability: adaptive | always | off\n" +
            "# adaptive = 服务端纠偏后一小段时间改用可靠同步；always = 客户端 snapshot 始终可靠；off = 不强制可靠。\n" +
            "syncReliability: \"adaptive\"\n" +
            "# 客户端本地玩家是否跳过物理挤压回写，用来降低高延迟下的回弹感。\n" +
            "noPlayerHitBox: true\n" +
            "# 收到服务端位置纠正后，客户端 snapshot 临时可靠发送的时间，单位毫秒。\n" +
            "correctionReliableMs: 1500\n" +
            "# 位置纠正超过这个距离时计为明显 rubberband，用于指标观测。\n" +
            "rubberbandDistance: 80\n" +
            "# 调试/脚本控制开关；true 会暂停本地玩家移动更新，普通服务器建议保持 false。\n" +
            "noUpdatePlayerMovement: false\n";
    }

    private static void installDefaultCompatibilityMiddleware(YZFPaths paths){
        arc.files.Fi readme = paths.compatDir.child("README.md");
        if(!readme.exists()){
            readme.writeString(
                "# MindustryYZF compatibility middleware\n" +
                "\n" +
                "Files in this directory are external hot-reloadable interface adapters.\n" +
                "They are evaluated before every YZF module script. Edit them while the server is running,\n" +
                "and the file watcher will reload YZF modules automatically.\n" +
                "\n" +
                "- 00-legacy-api.js: shared legacy globals and helpers.\n" +
                "- versions/159.2-interface.js: mappings for this Mindustry build.\n" +
                "\n" +
                "Use this directory to map old plugin APIs to new Mindustry/YZF APIs when behavior did not really change.\n"
            );
        }

        arc.files.Fi middleware = paths.compatDir.child("00-legacy-api.js");
        if(!middleware.exists()){
            middleware.writeString(
                "// External hot-reloadable compatibility middleware for legacy YZF plugins.\n" +
                "// This file is evaluated before every YZF module script. Add API aliases here\n" +
                "// when 159.2 moves a Mindustry/Arc/YZF symbol without changing behavior.\n" +
                "yzfCompat.install(function(yzf, yzfModule, compat, global){\n" +
                "  compat.alias('Core', Packages.arc.Core);\n" +
                "  compat.alias('Events', Packages.arc.Events);\n" +
                "  compat.alias('Timer', Packages.arc.util.Timer);\n" +
                "  compat.alias('Log', Packages.arc.util.Log);\n" +
                "  compat.alias('Vars', Packages.mindustry.Vars);\n" +
                "  compat.alias('Call', Packages.mindustry.gen.Call);\n" +
                "  compat.alias('Groups', Packages.mindustry.gen.Groups);\n" +
                "  compat.alias('Blocks', Packages.mindustry.content.Blocks);\n" +
                "  compat.alias('Items', Packages.mindustry.content.Items);\n" +
                "  compat.alias('Liquids', Packages.mindustry.content.Liquids);\n" +
                "  compat.alias('UnitTypes', Packages.mindustry.content.UnitTypes);\n" +
                "});\n"
            );
        }

        arc.files.Fi versionsDir = paths.compatDir.child("versions");
        versionsDir.mkdirs();
        arc.files.Fi versionMiddleware = versionsDir.child("159.2-interface.js");
        if(!versionMiddleware.exists()){
            versionMiddleware.writeString(
                "// Mindustry 159.2 external interface adapter.\n" +
                "// Edit this file after the server starts to adapt old plugin APIs without rebuilding the server.\n" +
                "// Saving this file triggers YZF hot reload through the file watcher.\n" +
                "\n" +
                "yzfCompat.install(function(yzf, yzfModule, compat, global){\n" +
                "  // Global class/package alias examples:\n" +
                "  // compat.alias('OldClassName', Packages.new.package.NewClassName);\n" +
                "  // compat.aliasPackage('OldBlocks', 'mindustry.content.Blocks');\n" +
                "\n" +
                "  // YZF API alias examples:\n" +
                "  // compat.aliasYzf('oldReloadAll', function(){ return yzf.runtime.reloadAll(); });\n" +
                "  // compat.aliasYzf('oldConfigGet', function(key, def){ return yzf.config.get(key, def); });\n" +
                "\n" +
                "  // Event alias examples:\n" +
                "  // compat.aliasEvent('OldEventName', 'NewEventName');\n" +
                "\n" +
                "  // Current migrated event names kept for old plugins:\n" +
                "  compat.aliasEvent('SendPacketEvent', 'SendPacketEvent');\n" +
                "  compat.aliasEvent('PlayerTeamChangedEvent', 'PlayerTeamChangedEvent');\n" +
                "  compat.aliasEvent('HealthChangedEvent', 'HealthChangedEvent');\n" +
                "  compat.aliasEvent('LogicAssembledEvent', 'LogicAssembledEvent');\n" +
                "});\n"
            );
        }
    }

    private static void installDefaultDriverTemplates(YZFPaths paths){
        arc.files.Fi readme = paths.driversDir.child("README.md");
        if(!readme.exists()){
            readme.writeString(
                "# YZF external drivers\n" +
                "\n" +
                "The server keeps only SQLite and local JSON built in.\n" +
                "MySQL, MariaDB, PostgreSQL, Redis, and MinIO are loaded from this directory.\n" +
                "\n" +
                "1. Build optional driver bundles with `./gradlew server:yzfDriverBundles` (Windows: `gradlew.bat server:yzfDriverBundles`).\n" +
                "2. Copy one of the generated folders from `server/build/yzf-driver-bundles/` into this directory.\n" +
                "3. Keep `driver-index.hjson` in sync with the folder names below.\n" +
                "\n" +
                "Default folders:\n" +
                "- mysql-default/\n" +
                "- mariadb-default/\n" +
                "- postgresql-default/\n" +
                "- redis-default/\n" +
                "- minio-default/\n"
            );
        }

        if(!paths.driverRegistryFile.exists()){
            paths.driverRegistryFile.writeString(
                "{\n" +
                "  drivers: [\n" +
                "    {\n" +
                "      id: \"mysql-default\"\n" +
                "      type: \"jdbc\"\n" +
                "      enabled: true\n" +
                "      description: \"External MySQL JDBC driver bundle\"\n" +
                "      path: \"mysql-default\"\n" +
                "      driverClassName: \"com.mysql.cj.jdbc.Driver\"\n" +
                "      serviceTypes: [\"mysql\"]\n" +
                "    },\n" +
                "    {\n" +
                "      id: \"mariadb-default\"\n" +
                "      type: \"jdbc\"\n" +
                "      enabled: true\n" +
                "      description: \"External MariaDB JDBC driver bundle\"\n" +
                "      path: \"mariadb-default\"\n" +
                "      driverClassName: \"org.mariadb.jdbc.Driver\"\n" +
                "      serviceTypes: [\"mariadb\"]\n" +
                "    },\n" +
                "    {\n" +
                "      id: \"postgresql-default\"\n" +
                "      type: \"jdbc\"\n" +
                "      enabled: true\n" +
                "      description: \"External PostgreSQL JDBC driver bundle\"\n" +
                "      path: \"postgresql-default\"\n" +
                "      driverClassName: \"org.postgresql.Driver\"\n" +
                "      serviceTypes: [\"postgresql\", \"postgres\"]\n" +
                "    },\n" +
                "    {\n" +
                "      id: \"redis-default\"\n" +
                "      type: \"library\"\n" +
                "      enabled: true\n" +
                "      description: \"External Redis/Jedis driver bundle\"\n" +
                "      path: \"redis-default\"\n" +
                "      serviceTypes: [\"redis\"]\n" +
                "    },\n" +
                "    {\n" +
                "      id: \"minio-default\"\n" +
                "      type: \"library\"\n" +
                "      enabled: true\n" +
                "      description: \"External MinIO client bundle\"\n" +
                "      path: \"minio-default\"\n" +
                "      serviceTypes: [\"minio\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n"
            );
        }
    }

    private static YZFPlayerSqlStore resolvePlayerSqlStore(YZFPaths paths, YZFServiceManager services){
        YZFPlayerStorageConfig config = YZFPlayerStorageConfigLoader.load(paths.playerStorageConfigFile);
        if(config == null || !config.enabled || YZFText.blank(config.serviceId)) return null;
        YZFSqlClient client = services.registry().getAs(config.serviceId, YZFSqlClient.class);
        if(client == null){
            Log.warn("[@] player storage service not found: @", name, config.serviceId);
            return null;
        }
        String type = client.config() == null ? "" : client.config().type == null ? "" : client.config().type.trim().toLowerCase();
        if(!config.allowedTypes.contains(type)){
            Log.warn("[@] player storage service type not allowed: @", name, type);
            return null;
        }
        return new YZFPlayerSqlStore(client);
    }

    private static void installDefaultServiceTemplates(YZFPaths paths){
        arc.files.Fi mariadb = paths.servicesDir.child("mariadb-default.hjson");
        if(!mariadb.exists()){
            mariadb.writeString(
                "# MariaDB player storage template\n" +
                "# 默认关闭。如需启用，把 enabled 改成 true，并在 player-storage.hjson 中选择本服务。\n" +
                "{\n" +
                "  id: \"player-mariadb\"\n" +
                "  type: \"mariadb\"\n" +
                "  driverId: \"mariadb-default\"\n" +
                "  enabled: false\n" +
                "  endpoint: \"127.0.0.1:3306\"\n" +
                "  database: \"mindustry_player\"\n" +
                "  username: \"root\"\n" +
                "  password: \"change-me\"\n" +
                "  options: [\n" +
                "    \"useUnicode=true\"\n" +
                "    \"characterEncoding=utf8\"\n" +
                "    \"serverTimezone=Asia/Shanghai\"\n" +
                "  ]\n" +
                "}\n"
            );
        }

        arc.files.Fi mysql = paths.servicesDir.child("mysql-default.hjson");
        if(!mysql.exists()){
            mysql.writeString(
                "# MySQL player storage template\n" +
                "# 默认关闭。如需启用，把 enabled 改成 true，并在 player-storage.hjson 中选择本服务。\n" +
                "{\n" +
                "  id: \"player-mysql\"\n" +
                "  type: \"mysql\"\n" +
                "  driverId: \"mysql-default\"\n" +
                "  enabled: false\n" +
                "  endpoint: \"127.0.0.1:3306\"\n" +
                "  database: \"mindustry_player\"\n" +
                "  username: \"root\"\n" +
                "  password: \"change-me\"\n" +
                "  options: [\n" +
                "    \"useUnicode=true\"\n" +
                "    \"characterEncoding=utf8\"\n" +
                "    \"serverTimezone=Asia/Shanghai\"\n" +
                "  ]\n" +
                "}\n"
            );
        }

        arc.files.Fi postgresql = paths.servicesDir.child("postgresql-default.hjson");
        if(!postgresql.exists()){
            postgresql.writeString(
                "# PostgreSQL player storage template\n" +
                "# 默认关闭。如需启用，把 enabled 改成 true，并在 player-storage.hjson 中选择本服务。\n" +
                "{\n" +
                "  id: \"player-postgresql\"\n" +
                "  type: \"postgresql\"\n" +
                "  driverId: \"postgresql-default\"\n" +
                "  enabled: false\n" +
                "  endpoint: \"127.0.0.1:5432\"\n" +
                "  database: \"mindustry_player\"\n" +
                "  username: \"postgres\"\n" +
                "  password: \"change-me\"\n" +
                "  options: [\n" +
                "    \"ssl=false\"\n" +
                "    \"stringtype=unspecified\"\n" +
                "  ]\n" +
                "}\n"
            );
        }

        arc.files.Fi sqlite = paths.servicesDir.child("sqlite-default.hjson");
        if(!sqlite.exists()){
            sqlite.writeString(
                "# SQLite player storage template\n" +
                "# 首次启动默认启用，作为本地优先数据库。\n" +
                "# 其他插件可通过 SQL / DB bridge 调用此数据库。\n" +
                "{\n" +
                "  id: \"player-sqlite\"\n" +
                "  type: \"sqlite\"\n" +
                "  enabled: true\n" +
                "  databaseFile: \"config/yzf/config/services/player-sqlite.db\"\n" +
                "}\n"
            );
        }

        arc.files.Fi redis = paths.servicesDir.child("redis-default.hjson");
        if(!redis.exists()){
            redis.writeString(
                "# Redis service template\n" +
                "# Disabled by default. Supports local or remote Redis service.\n" +
                "# Set enabled=true after filling endpoint / password / nodes as needed.\n" +
                "{\n" +
                "  id: \"service-redis\"\n" +
                "  type: \"redis\"\n" +
                "  driverId: \"redis-default\"\n" +
                "  enabled: false\n" +
                "  clusterMode: \"standalone\"\n" +
                "  endpoint: \"127.0.0.1:6379\"\n" +
                "  username: \"\"\n" +
                "  password: \"\"\n" +
                "  namespace: \"yzf\"\n" +
                "  connectTimeoutMs: 10000\n" +
                "  readTimeoutMs: 15000\n" +
                "  nodes: []\n" +
                "  options: [\n" +
                "    \"db=0\"\n" +
                "    \"masterName=mymaster\"\n" +
                "  ]\n" +
                "}\n"
            );
        }

        arc.files.Fi minio = paths.servicesDir.child("minio-default.hjson");
        if(!minio.exists()){
            minio.writeString(
                "# MinIO object storage template\n" +
                "# Disabled by default. Supports local or remote MinIO / S3-compatible endpoint.\n" +
                "# Set enabled=true after filling endpoint / accessKey / secretKey / bucket.\n" +
                "{\n" +
                "  id: \"service-minio\"\n" +
                "  type: \"minio\"\n" +
                "  driverId: \"minio-default\"\n" +
                "  enabled: false\n" +
                "  clusterMode: \"standalone\"\n" +
                "  endpoint: \"http://127.0.0.1:9000\"\n" +
                "  bucket: \"mindustry-yzf\"\n" +
                "  accessKey: \"minioadmin\"\n" +
                "  secretKey: \"minioadmin\"\n" +
                "  region: \"us-east-1\"\n" +
                "  namespace: \"yzf\"\n" +
                "  connectTimeoutMs: 10000\n" +
                "  readTimeoutMs: 15000\n" +
                "}\n"
            );
        }

        arc.files.Fi selection = paths.configDir.child("player-storage.hjson");
        if(!selection.exists()){
            selection.writeString(
                "# Player storage selector\n" +
                "# 只能选择一种玩家存储数据库。\n" +
                "# 默认首次启动使用本地 SQLite。\n" +
                "{\n" +
                "  enabled: true\n" +
                "  serviceId: \"player-sqlite\"\n" +
                "  allowedTypes: [\"sqlite\", \"mysql\", \"mariadb\", \"postgresql\"]\n" +
                "  note: \"Choose exactly one player storage database service and set enabled=true.\"\n" +
                "}\n"
            );
        }
    }

    private static void installDefaultDatabaseTemplates(YZFPaths paths){
        arc.files.Fi localJsonConfig = paths.localDatabaseConfigFile;
        if(!localJsonConfig.exists()){
            localJsonConfig.writeString(
                "# Pseudo-local JSON database config\n" +
                "# 默认关闭。只有手动改成 enabled=true 才会启用。\n" +
                "{\n" +
                "  id: \"local\"\n" +
                "  name: \"Local JSON Database\"\n" +
                "  type: \"local\"\n" +
                "  enabled: true\n" +
                "  readOnly: false\n" +
                "  description: \"Built-in folder-based local JSON database. Enabled by default.\"\n" +
                "}\n"
            );
        }

        arc.files.Fi comidConfig = paths.comidConfigFile;
        if(!comidConfig.exists()){
            comidConfig.writeString(
                "# COMID persistence fallback config\n" +
                "# 默认关闭 comid-registry.json 文件回退。\n" +
                "# 若设为 true，当没有可用数据库后端时将写入 data/comid-registry.json。\n" +
                "{\n" +
                "  allowLegacyFileFallback: false\n" +
                "}\n"
            );
        }

        arc.files.Fi remoteJson = paths.root.child("remote-json-database.template.json");
        if(!remoteJson.exists()){
            remoteJson.writeString(
                "{\n" +
                "  \"_comment\": \"Remote JSON database template. 默认关闭，需要手动合并到 data/database-registry.json。\",\n" +
                "  \"description\": \"Rename this file to database-registry.json or merge its remote entry into data/database-registry.json to enable a remote JSON database.\",\n" +
                "  \"databases\": [\n" +
                "    {\n" +
                "      \"id\": \"remote-json-default\",\n" +
                "      \"name\": \"Remote JSON Database\",\n" +
                "      \"type\": \"remote\",\n" +
                "      \"enabled\": false,\n" +
                "      \"readOnly\": false,\n" +
                "      \"description\": \"Example remote JSON database entry.\",\n" +
                "      \"endpoint\": \"http://127.0.0.1:8080/api/db\",\n" +
                "      \"serviceId\": \"\",\n" +
                "      \"basePath\": \"\",\n" +
                "      \"sourcePath\": \"\"\n" +
                "    }\n" +
                "  ]\n" +
                "}\n"
            );
        }

        arc.files.Fi localJsonReadme = paths.root.child("local-json-database.txt");
        // Rewrite legacy generated text that may contain a machine-specific
        // absolute path, so copied server folders remain portable.
        if(!localJsonReadme.exists() || localJsonReadme.readString().matches("(?s).*[A-Za-z]:[\\\\/].*")){
            localJsonReadme.writeString(
                "Local JSON database now has its own config file and is enabled by default.\n" +
                "\n" +
                "Database ID: local\n" +
                "Config file: " + paths.relative(paths.localDatabaseConfigFile) + "\n" +
                "COMID fallback config: " + paths.relative(paths.comidConfigFile) + "\n" +
                "Storage directory: " + paths.relative(paths.databasesDir.child("local")) + "\n" +
                "Registry file: " + paths.relative(paths.databaseRegistryFile) + "\n" +
                "Player storage selector: " + paths.relative(paths.configDir.child("player-storage.hjson")) + "\n" +
                "Default SQLite template: " + paths.relative(paths.servicesDir.child("sqlite-default.hjson")) + "\n" +
                "Default SQLite database file: config/yzf/config/services/player-sqlite.db\n" +
                "\n" +
                "Driver index: " + paths.relative(paths.driverRegistryFile) + "\n" +
                "Driver directory: " + paths.relative(paths.driversDir) + "\n" +
                "\n" +
                "Supported database templates created on startup:\n" +
                "- config/databases/local-json.hjson (enabled by default)\n" +
                "- config/databases/comid-storage.hjson (legacy file fallback disabled by default)\n" +
                "- config/services/mariadb-default.hjson (disabled by default)\n" +
                "- config/services/minio-default.hjson (disabled by default)\n" +
                "- config/services/mysql-default.hjson (disabled by default)\n" +
                "- config/services/postgresql-default.hjson (disabled by default)\n" +
                "- config/services/redis-default.hjson (disabled by default)\n" +
                "- config/services/sqlite-default.hjson (enabled by default)\n" +
                "- config/drivers/driver-index.hjson\n" +
                "- config/player-storage.hjson (enabled and points to player-sqlite by default)\n" +
                "- remote-json-database.template.json\n"
            );
        }
    }

    public static long ensurePlayerComid(mindustry.gen.Player player){
        if(player == null || player.uuid() == null) return -1;
        try{
            if(context == null) return -1;
            long comid = context.comidRegistry.getOrCreate(player.uuid());
            if(comid > 0L && context.playerDataStore != null && context.playerDataStore.sqlStore() != null){
                context.playerDataStore.sqlStore().touchPlayerProfile(
                    player.uuid(),
                    comid,
                    player.name(),
                    player.ip(),
                    System.currentTimeMillis()
                );
            }
            return comid;
        }catch(Exception e){
            Log.warn("[@] Failed to ensure comid for player @", name, player.uuid(), e);
            return -1;
        }
    }
}
