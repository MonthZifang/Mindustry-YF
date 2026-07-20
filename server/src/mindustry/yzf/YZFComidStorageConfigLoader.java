package mindustry.yzf;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;

public final class YZFComidStorageConfigLoader{
    private YZFComidStorageConfigLoader(){
    }

    public static YZFComidStorageConfig load(Fi file){
        YZFComidStorageConfig config = new YZFComidStorageConfig();
        config.allowLegacyFileFallback = false;
        if(file == null || !file.exists()) return config;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(file));
            if(root != null && root.isObject()){
                config.allowLegacyFileFallback = root.getBool("allowLegacyFileFallback", false);
            }
        }catch(Exception e){
            Log.err("[@] Failed to load COMID storage config", MindustryYZF.name, e);
        }
        return config;
    }
}
