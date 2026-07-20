package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 玩家数据持久化存储，按 comid 索引。
 * 每个玩家的数据以 JSON 对象存储在单个文件中。
 * 支持 get/set 操作，键值对形式。
 */
public final class YZFPlayerDataStore{
    private final File dataDir;
    private final YZFComIdRegistry comidRegistry;
    private final YZFPlayerSqlStore sqlStore;
    // comid -> {key -> value}
    private final ObjectMap<Long, ObjectMap<String, String>> cache = new ObjectMap<>();

    public YZFPlayerDataStore(File dataDir, YZFComIdRegistry comidRegistry){
        this(dataDir, comidRegistry, null);
    }

    public YZFPlayerDataStore(File dataDir, YZFComIdRegistry comidRegistry, YZFPlayerSqlStore sqlStore){
        this.dataDir = new File(dataDir, "player-data");
        this.comidRegistry = comidRegistry;
        this.sqlStore = sqlStore;
        this.dataDir.mkdirs();
        if(this.sqlStore != null){
            this.sqlStore.ensureSchema();
        }
    }

    public YZFPlayerSqlStore sqlStore(){
        return sqlStore;
    }

    /**
     * 通过 comid 获取玩家数据
     */
    public String get(long comid, String key){
        ObjectMap<String, String> data = loadPlayerData(comid);
        return data.get(key);
    }

    /**
     * 通过 comid 获取玩家数据，带默认值
     */
    public String get(long comid, String key, String defaultValue){
        String val = get(comid, key);
        return val != null ? val : defaultValue;
    }

    /**
     * 通过 comid 设置玩家数据
     */
    public void set(long comid, String key, String value){
        ObjectMap<String, String> data = loadPlayerData(comid);
        if(value == null){
            data.remove(key);
        }else{
            data.put(key, value);
        }
        savePlayerData(comid, data);
    }

    /**
     * 通过 comid 获取整数数据
     */
    public int getInt(long comid, String key, int defaultValue){
        String val = get(comid, key);
        if(val == null) return defaultValue;
        try{
            return Integer.parseInt(val);
        }catch(NumberFormatException e){
            return defaultValue;
        }
    }

    /**
     * 通过 comid 设置整数数据
     */
    public void setInt(long comid, String key, int value){
        set(comid, key, String.valueOf(value));
    }

    /**
     * 通过 comid 获取布尔数据
     */
    public boolean getBool(long comid, String key, boolean defaultValue){
        String val = get(comid, key);
        if(val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }

    /**
     * 通过 comid 设置布尔数据
     */
    public void setBool(long comid, String key, boolean value){
        set(comid, key, String.valueOf(value));
    }

    /**
     * 通过 comid 获取浮点数据
     */
    public double getDouble(long comid, String key, double defaultValue){
        String val = get(comid, key);
        if(val == null) return defaultValue;
        try{
            return Double.parseDouble(val);
        }catch(NumberFormatException e){
            return defaultValue;
        }
    }

    /**
     * 通过 comid 设置浮点数据
     */
    public void setDouble(long comid, String key, double value){
        set(comid, key, String.valueOf(value));
    }

    /**
     * 获取玩家所有数据的 JSON 字符串
     */
    public String getAll(long comid){
        ObjectMap<String, String> data = loadPlayerData(comid);
        Jval obj = Jval.newObject();
        for(ObjectMap.Entry<String, String> e : data){
            obj.put(e.key, e.value);
        }
        return obj.toString(Jval.Jformat.plain);
    }

    /**
     * 删除玩家某条数据
     */
    public void remove(long comid, String key){
        ObjectMap<String, String> data = loadPlayerData(comid);
        data.remove(key);
        savePlayerData(comid, data);
    }

    /**
     * 清空玩家所有数据
     */
    public void clear(long comid){
        cache.remove(comid);
        File file = playerFile(comid);
        if(file.exists()){
            file.delete();
        }
    }

    /**
     * 通过 UUID 获取数据（自动转换为 comid）
     */
    public String getByUuid(String uuid, String key){
        long comid = comidRegistry.getComid(uuid);
        if(comid < 0) return null;
        return get(comid, key);
    }

    /**
     * 通过 UUID 设置数据（自动转换为 comid）
     */
    public void setByUuid(String uuid, String key, String value){
        long comid = comidRegistry.getOrCreate(uuid);
        set(comid, key, value);
    }

    private ObjectMap<String, String> loadPlayerData(long comid){
        ObjectMap<String, String> cached = cache.get(comid);
        if(cached != null) return cached;

        ObjectMap<String, String> data = sqlStore != null ? sqlStore.loadPlayerData(comid) : new ObjectMap<>();
        if(sqlStore != null){
            cache.put(comid, data);
            return data;
        }
        File file = playerFile(comid);
        if(file.exists()){
            try{
                String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Jval root = Jval.read(json);
                if(root.isObject()){
                    for(var entry : root.asObject()){
                        String key = entry.key;
                        Jval val = entry.value;
                        data.put(key, val.toString(Jval.Jformat.plain));
                    }
                }
            }catch(Exception e){
                Log.err("[@] 加载玩家数据失败 comid=@", MindustryYZF.name, comid, e);
            }
        }
        cache.put(comid, data);
        return data;
    }

    private void savePlayerData(long comid, ObjectMap<String, String> data){
        cache.put(comid, data);
        if(sqlStore != null){
            sqlStore.savePlayerData(comid, data);
            return;
        }
        Jval obj = Jval.newObject();
        for(ObjectMap.Entry<String, String> e : data){
            obj.put(e.key, e.value);
        }
        File file = playerFile(comid);
        try(Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)){
            w.write(obj.toString(Jval.Jformat.formatted));
        }catch(Exception e){
            Log.err("[@] 保存玩家数据失败 comid=@", MindustryYZF.name, comid, e);
        }
    }

    private File playerFile(long comid){
        return new File(dataDir, comid + ".json");
    }
}
