package mindustry.yzf;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;

public final class YZFPlayerStorageConfigLoader{
    private YZFPlayerStorageConfigLoader(){
    }

    public static YZFPlayerStorageConfig load(Fi file){
        YZFPlayerStorageConfig config = new YZFPlayerStorageConfig();
        config.allowedTypes.add("sqlite", "mysql", "mariadb", "postgresql");
        if(file == null || !file.exists()) return config;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(file));
            if(root != null && root.isObject()){
                config.enabled = root.getBool("enabled", false);
                config.serviceId = root.getString("serviceId", "");
                if(root.has("allowedTypes") && root.get("allowedTypes").isArray()){
                    config.allowedTypes.clear();
                    for(Jval item : root.get("allowedTypes").asArray()){
                        if(item != null && item.isString()) config.allowedTypes.add(item.asString().trim().toLowerCase());
                    }
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to load player storage config", MindustryYZF.name, e);
        }
        return config;
    }
}
