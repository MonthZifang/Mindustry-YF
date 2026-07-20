package mindustry.yzf;

import mindustry.server.ServerControl;

public final class YZFContext{
    public final ServerControl serverControl;
    public final YZFPaths paths;
    public final YZFModuleRegistry registry;
    public final YZFScriptRuntime runtime;
    public final YZFFileWatcher watcher;
    public final YZFServiceManager services;
    public final YZFPermissionManager permissions;
    public final YZFMetrics metrics;
    public final YZFAuditLog audit;
    public final YZFComIdRegistry comidRegistry;
    public final YZFPlayerDataStore playerDataStore;
    public final YZFDatabaseRegistry databaseRegistry;
    public final YZFWebSocketManager wsManager;
    public final YZFContentRegistry contentRegistry;
    public final YZFCommandRegistry commandRegistry;
    public final YZFWebUiRegistry webUi;
    public final YZFModCommandInterface modCommands;
    public final String[] startupArgs;
    public volatile YZFRuntimeConfig runtimeConfig;
    public final YZFSecurityConfig securityConfig;
    public final YZFMemoryRegionManager memoryRegions;

    public YZFContext(ServerControl serverControl, YZFPaths paths, YZFModuleRegistry registry, YZFScriptRuntime runtime, YZFFileWatcher watcher, YZFServiceManager services, YZFPermissionManager permissions, YZFMetrics metrics, YZFAuditLog audit, YZFComIdRegistry comidRegistry, YZFPlayerDataStore playerDataStore, YZFDatabaseRegistry databaseRegistry, YZFWebSocketManager wsManager, YZFContentRegistry contentRegistry, YZFCommandRegistry commandRegistry, YZFWebUiRegistry webUi, YZFModCommandInterface modCommands, YZFSecurityConfig securityConfig, String[] startupArgs, YZFMemoryRegionManager memoryRegions){
        this.serverControl = serverControl;
        this.paths = paths;
        this.registry = registry;
        this.runtime = runtime;
        this.watcher = watcher;
        this.services = services;
        this.permissions = permissions;
        this.metrics = metrics;
        this.audit = audit;
        this.comidRegistry = comidRegistry;
        this.playerDataStore = playerDataStore;
        this.databaseRegistry = databaseRegistry;
        this.wsManager = wsManager;
        this.contentRegistry = contentRegistry;
        this.commandRegistry = commandRegistry;
        this.webUi = webUi;
        this.modCommands = modCommands;
        this.startupArgs = startupArgs;
        this.runtimeConfig = YZFRuntimeConfig.load(paths);
        this.securityConfig = securityConfig;
        this.memoryRegions = memoryRegions;
    }

    public synchronized void reloadRuntimeConfig(){
        this.runtimeConfig = YZFRuntimeConfig.load(paths);
        YZFErrorLog.configure(paths, runtimeConfig.errorLoggingEnabled, runtimeConfig.errorTerminalColors);
    }
}
