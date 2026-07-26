package mindustry.yzf;

import arc.files.Fi;

import java.nio.file.Path;

public final class YZFPaths{
    public final Fi root;
    public final Fi scriptsDir;
    public final Fi modulesDir;
    public final Fi pluginsDir;
    public final Fi cacheDir;
    public final Fi compatDir;
    public final Fi logsDir;
    public final Fi configDir;
    public final Fi dataDir;
    public final Fi databasesDir;
    public final Fi databaseConfigsDir;
    public final Fi databaseRegistryFile;
    public final Fi localDatabaseConfigFile;
    public final Fi playerStorageConfigFile;
    public final Fi comidConfigFile;
    public final Fi servicesDir;
    public final Fi driversDir;
    public final Fi driverRegistryFile;
    public final Fi remotesDir;
    public final Fi runtimeSdkDir;
    public final Fi permissionsFile;
    public final Fi terminalFile;
    public final Fi securityFile;
    public final Fi externalAccessFile;
    public final Fi stableApiFile;
    public final Fi stableApiDebugFile;
    public final Fi syncConfigFile;
    public final Fi runtimeConfigFile;
    public final Fi memoryRegionsConfigFile;
    public final Fi auditFile;

    private YZFPaths(Fi root){
        this.root = root;
        this.scriptsDir = root.child("scripts");
        this.modulesDir = root.child("modules");
        this.pluginsDir = root.child("plugins");
        this.cacheDir = root.child("cache");
        this.compatDir = root.child("compat");
        this.logsDir = root.child("logs");
        this.configDir = root.child("config");
        this.dataDir = root.child("data");
        this.databasesDir = dataDir.child("databases");
        this.databaseConfigsDir = configDir.child("databases");
        this.databaseRegistryFile = dataDir.child("database-registry.json");
        this.localDatabaseConfigFile = databaseConfigsDir.child("local-json.hjson");
        this.playerStorageConfigFile = configDir.child("player-storage.hjson");
        this.comidConfigFile = databaseConfigsDir.child("comid-storage.hjson");
        this.servicesDir = configDir.child("services");
        this.driversDir = configDir.child("drivers");
        this.driverRegistryFile = driversDir.child("driver-index.hjson");
        this.remotesDir = configDir.child("remotes");
        this.runtimeSdkDir = root.child("runtime-sdk");
        this.permissionsFile = configDir.child("permissions.hjson");
        this.terminalFile = configDir.child("terminal.hjson");
        this.securityFile = configDir.child("security.hjson");
        this.externalAccessFile = configDir.child("external-access.hjson");
        this.stableApiFile = configDir.child("stable-api.hjson");
        this.stableApiDebugFile = configDir.child("stable-api-debug.js");
        this.syncConfigFile = configDir.child("sync.hjson");
        this.runtimeConfigFile = configDir.child("runtime.hjson");
        this.memoryRegionsConfigFile = configDir.child("memory-regions.hjson");
        this.auditFile = logsDir.child("yzf-audit.log");
    }

    public static YZFPaths create(Fi root){
        return new YZFPaths(root);
    }

    /** Returns a portable path for data stored in the YZF configuration tree. */
    public String relative(Fi file){
        try{
            Path base = root.file().toPath().toAbsolutePath().normalize();
            Path target = file.file().toPath().toAbsolutePath().normalize();
            if(target.startsWith(base)){
                return base.relativize(target).toString().replace('\\', '/');
            }
        }catch(Exception ignored){
        }
        return file.name();
    }

    public void ensureLayout(){
        root.mkdirs();
        scriptsDir.mkdirs();
        modulesDir.mkdirs();
        pluginsDir.mkdirs();
        cacheDir.mkdirs();
        compatDir.mkdirs();
        logsDir.mkdirs();
        configDir.mkdirs();
        dataDir.mkdirs();
        databasesDir.mkdirs();
        databaseConfigsDir.mkdirs();
        servicesDir.mkdirs();
        driversDir.mkdirs();
        remotesDir.mkdirs();
        runtimeSdkDir.mkdirs();
    }
}
