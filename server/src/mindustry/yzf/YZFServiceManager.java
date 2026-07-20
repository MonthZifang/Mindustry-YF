package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.util.Locale;

public final class YZFServiceManager{
    private final YZFPaths paths;
    private final YZFDriverRegistry driverRegistry;
    private final YZFServiceRegistry registry = new YZFServiceRegistry();
    private final Seq<YZFServiceConfig> configs = new Seq<>();

    public YZFServiceManager(YZFPaths paths, YZFDriverRegistry driverRegistry){
        this.paths = paths;
        this.driverRegistry = driverRegistry;
    }

    public synchronized void reload(){
        stopAll();
        configs.clear();
        registry.clear();
        driverRegistry.reload();
        loadConfigs();
        startAll();
    }

    public synchronized Seq<YZFServiceConfig> configs(){
        return configs.copy();
    }

    public synchronized YZFServiceRegistry registry(){
        return registry;
    }

    public synchronized YZFServiceConfig findConfig(String id){
        return configs.find(c -> c.id.equals(id));
    }

    public synchronized void stopAll(){
        for(YZFServiceClient service : registry.all()){
            try{
                service.stop();
            }catch(Exception ignored){
            }
        }
        registry.clear();
    }

    public synchronized void shutdown(){
        stopAll();
        driverRegistry.shutdown();
    }

    private void loadConfigs(){
        if(!paths.servicesDir.exists()) return;
        for(Fi file : paths.servicesDir.list()){
            if(file.isDirectory()) continue;
            if(!(file.extEquals("hjson") || file.extEquals("json"))) continue;
            YZFServiceConfig config = parse(file);
            if(config != null){
                configs.add(config);
            }
        }
    }

    private void startAll(){
        for(YZFServiceConfig config : configs){
            if(!config.enabled) continue;
            YZFServiceClient client = null;
            try{
                client = YZFServiceFactory.create(config, driverRegistry);
                client.start();
                registry.put(client);
                if(MindustryYZF.context() != null){
                    MindustryYZF.context().metrics.serviceLoads.incrementAndGet();
                }
                Log.info("[@] 服务已启动: @ -> @", MindustryYZF.name, config.id, client.summary());
            }catch(Exception e){
                if(MindustryYZF.context() != null){
                    MindustryYZF.context().metrics.serviceFailures.incrementAndGet();
                    MindustryYZF.context().metrics.markFailure("service:" + config.id + ": " + e.getMessage());
                }
                Log.err("[@] 服务启动失败: @", MindustryYZF.name, config.id, e);
                if(client != null){
                    try{
                        client.stop();
                    }catch(Exception cleanupError){
                        Log.err("[@] Failed to clean up partially started service: @", MindustryYZF.name, config.id, cleanupError);
                    }
                }
            }
        }
    }

    private YZFServiceConfig parse(Fi file){
        try{
            Jval root = Jval.read(YZFText.readTextSmart(file));
            YZFServiceConfig config = new YZFServiceConfig();
            config.id = root.getString("id", file.nameWithoutExtension());
            config.sourcePath = paths.relative(file);
            config.type = root.getString("type", "").trim().toLowerCase(Locale.ROOT);
            if(YZFText.blank(config.type)){
                Log.err("[@] 读取服务配置失败: @ 缺少 type 字段", MindustryYZF.name, file.absolutePath());
                return null;
            }
            config.enabled = root.getBool("enabled", true);
            config.clusterMode = parseClusterMode(root.getString("clusterMode", "standalone"), file.absolutePath());
            config.endpoint = root.getString("endpoint", "");
            config.database = root.getString("database", "");
            config.databaseFile = root.getString("databaseFile", "");
            config.driverId = root.getString("driverId", "");
            config.driverClassName = root.getString("driverClassName", "");
            config.bucket = root.getString("bucket", "");
            config.username = root.getString("username", "");
            config.password = root.getString("password", "");
            config.accessKey = root.getString("accessKey", "");
            config.secretKey = root.getString("secretKey", "");
            config.region = root.getString("region", "");
            config.namespace = root.getString("namespace", "");
            config.connectTimeoutMs = root.getInt("connectTimeoutMs", config.connectTimeoutMs);
            config.readTimeoutMs = root.getInt("readTimeoutMs", config.readTimeoutMs);
            if(root.has("nodes") && root.get("nodes").isArray()){
                for(Jval child : root.get("nodes").asArray()){
                    if(child.isString()) config.nodes.add(child.asString());
                }
            }
            if(root.has("options") && root.get("options").isArray()){
                for(Jval child : root.get("options").asArray()){
                    if(child.isString()) config.options.add(child.asString());
                }
            }
            return config;
        }catch(Exception e){
            Log.err("[@] 读取服务配置失败: @", MindustryYZF.name, file.absolutePath(), e);
            return null;
        }
    }

    private YZFClusterMode parseClusterMode(String raw, String path){
        if(YZFText.blank(raw)) return YZFClusterMode.standalone;
        try{
            return YZFClusterMode.valueOf(raw.trim().toLowerCase(Locale.ROOT));
        }catch(Exception e){
            Log.warn("[@] @ 的 clusterMode 无效: @，已回退为 standalone", MindustryYZF.name, path, raw);
            return YZFClusterMode.standalone;
        }
    }
}
