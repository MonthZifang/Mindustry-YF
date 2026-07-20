package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * comid registry backed by the default local JSON database.
 * Each UUID maps to exactly one comid and each comid maps back to exactly one UUID.
 */
public final class YZFComIdRegistry{
    private static final String databaseCategory = "system";
    private static final String databaseKey = "comid";
    private static final String legacyDatabaseKey = "comid-registry";

    private final YZFDatabaseRegistry databaseRegistry;
    private final File legacyFile;
    private final YZFPlayerSqlStore sqlStore;
    private final boolean allowLegacyFileFallback;
    private final ObjectMap<String, Long> uuidToComid = new ObjectMap<>();
    private final ObjectMap<Long, String> comidToUuid = new ObjectMap<>();
    private final ObjectMap<Integer, Seq<Long>> usedByDigit = new ObjectMap<>();
    private final Random random = new Random();
    private int currentDigits = 5;

    public YZFComIdRegistry(YZFPaths paths, YZFDatabaseRegistry databaseRegistry){
        this(paths, databaseRegistry, null);
    }

    public YZFComIdRegistry(YZFPaths paths, YZFDatabaseRegistry databaseRegistry, YZFPlayerSqlStore sqlStore){
        this.databaseRegistry = databaseRegistry;
        this.legacyFile = paths.root.child("data").file().toPath().resolve("comid-registry.json").toFile();
        this.sqlStore = sqlStore;
        this.allowLegacyFileFallback = YZFComidStorageConfigLoader.load(paths.comidConfigFile).allowLegacyFileFallback;
        if(this.sqlStore != null){
            this.sqlStore.ensureSchema();
        }
        load();
        detectCurrentDigits();
    }

    /**
     * Returns the existing comid for the UUID or creates one if missing.
     */
    public synchronized long getOrCreate(String uuid){
        if(uuid == null || uuid.trim().isEmpty()){
            throw new IllegalArgumentException("uuid cannot be blank");
        }
        uuid = uuid.trim();
        if(!isValidUuid(uuid)){
            throw new IllegalArgumentException("invalid uuid: " + uuid);
        }
        Long existing = uuidToComid.get(uuid);
        if(existing != null) return existing;
        return allocate(uuid);
    }

    /**
     * Gets the UUID for a comid.
     */
    public synchronized String getUuid(long comid){
        return comidToUuid.get(comid);
    }

    /**
     * Gets the comid for a UUID, or -1 if it does not exist.
     */
    public synchronized long getComid(String uuid){
        if(uuid == null || uuid.trim().isEmpty()) return -1;
        uuid = uuid.trim();
        if(!isValidUuid(uuid)) return -1;
        Long c = uuidToComid.get(uuid);
        return c != null ? c : -1;
    }

    private boolean isValidUuid(String uuid){
        if(uuid == null) return false;
        String trimmed = uuid.trim();
        if(trimmed.isEmpty()) return false;
        if(trimmed.matches("(?i)^COM-[A-Z0-9]+$")) return false;
        return true;
    }

    /**
     * Checks whether a comid exists.
     */
    public synchronized boolean exists(long comid){
        return comidToUuid.containsKey(comid);
    }

    public synchronized int currentDigits(){
        return currentDigits;
    }

    public synchronized long remainingInCurrentDigits(){
        long min = (long)Math.pow(10, currentDigits - 1);
        long max = (long)Math.pow(10, currentDigits) - 1;
        long total = max - min + 1;
        Seq<Long> used = usedByDigit.get(currentDigits);
        return total - (used != null ? used.size : 0);
    }

    public synchronized int totalRegistered(){
        return uuidToComid.size;
    }

    private long allocate(String uuid){
        while(true){
            long min = (long)Math.pow(10, currentDigits - 1);
            long max = (long)Math.pow(10, currentDigits) - 1;
            long total = max - min + 1;
            Seq<Long> used = usedByDigit.get(currentDigits);
            if(used == null){
                used = new Seq<>();
                usedByDigit.put(currentDigits, used);
            }

            if(used.size >= total){
                currentDigits++;
                Log.info("[@] comid digit count upgraded to @", MindustryYZF.name, currentDigits);
                continue;
            }

            long comid;
            int maxAttempts = (int)Math.min(total, 10000);
            int attempts = 0;
            do{
                comid = min + (long)(random.nextDouble() * total);
                attempts++;
                if(attempts > maxAttempts){
                    for(long i = min; i <= max; i++){
                        if(!comidToUuid.containsKey(i)){
                            comid = i;
                            break;
                        }
                    }
                    break;
                }
            }while(comidToUuid.containsKey(comid));

            uuidToComid.put(uuid, comid);
            comidToUuid.put(comid, uuid);
            used.add(comid);
            save();

            Log.info("[@] comid assigned: UUID=@ -> comid=@", MindustryYZF.name, uuid, comid);
            return comid;
        }
    }

    private void detectCurrentDigits(){
        int maxDigits = 5;
        for(Long comid : comidToUuid.keys()){
            int d = String.valueOf(comid).length();
            if(d > maxDigits) maxDigits = d;
        }
        currentDigits = maxDigits;

        long min = (long)Math.pow(10, currentDigits - 1);
        long max = (long)Math.pow(10, currentDigits) - 1;
        long total = max - min + 1;
        Seq<Long> used = usedByDigit.get(currentDigits);
        if(used != null && used.size >= total){
            currentDigits++;
        }
    }

    private void load(){
        uuidToComid.clear();
        comidToUuid.clear();
        usedByDigit.clear();

        boolean loaded = loadFromDatabase();
        if(!loaded && allowLegacyFileFallback){
            loaded = loadLegacyFile();
            if(loaded){
                save();
            }
        }

        Log.info("[@] comid registry loaded: @ entries", MindustryYZF.name, uuidToComid.size);
    }

    private boolean loadFromDatabase(){
        if(sqlStore != null){
            ObjectMap<String, Long> mappings = sqlStore.loadComidMappings();
            if(mappings.isEmpty()) return false;
            for(ObjectMap.Entry<String, Long> entry : mappings){
                String uuid = entry.key;
                long comid = entry.value;
                uuidToComid.put(uuid, comid);
                comidToUuid.put(comid, uuid);
                int digits = String.valueOf(comid).length();
                Seq<Long> used = usedByDigit.get(digits);
                if(used == null){
                    used = new Seq<>();
                    usedByDigit.put(digits, used);
                }
                used.add(comid);
            }
            return true;
        }
        if(databaseRegistry == null) return false;
        try{
            if(!databaseRegistry.has(databaseRegistry.defaultId())) return false;
            String raw = databaseRegistry.get(databaseRegistry.defaultId(), databaseCategory, databaseKey);
            if(raw == null || raw.trim().isEmpty()){
                raw = databaseRegistry.get(databaseRegistry.defaultId(), databaseCategory, legacyDatabaseKey);
            }
            if(raw == null || raw.trim().isEmpty()) return false;
            return loadFromJson(raw);
        }catch(Exception e){
            Log.err("[@] Failed to load comid registry from local database", MindustryYZF.name, e);
            return false;
        }
    }

    private boolean loadLegacyFile(){
        if(legacyFile == null || !legacyFile.exists()) return false;
        try{
            String json = new String(java.nio.file.Files.readAllBytes(legacyFile.toPath()));
            return loadFromJson(json);
        }catch(Exception e){
            Log.err("[@] Failed to load legacy comid registry file", MindustryYZF.name, e);
            return false;
        }
    }

    private boolean loadFromJson(String json){
        if(json == null || json.trim().isEmpty()) return false;
        try{
            Jval root = Jval.read(json);
            if(root == null || !root.isObject()) return false;

            Jval entries = root.get("entries");
            if(entries != null && entries.isArray()){
                for(Jval entry : entries.asArray()){
                    long comid = entry.getLong("comid", -1);
                    String uuid = entry.getString("uuid", null);
                    if(comid > 0 && uuid != null){
                        uuid = uuid.trim();
                        if(uuid.isEmpty()) continue;
                        if(!isValidUuid(uuid)) continue;
                        uuidToComid.put(uuid, comid);
                        comidToUuid.put(comid, uuid);
                        int digits = String.valueOf(comid).length();
                        Seq<Long> used = usedByDigit.get(digits);
                        if(used == null){
                            used = new Seq<>();
                            usedByDigit.put(digits, used);
                        }
                        used.add(comid);
                    }
                }
            }

            if(root.has("currentDigits")){
                currentDigits = Math.max(5, root.getInt("currentDigits", currentDigits));
            }
            return true;
        }catch(Exception e){
            Log.err("[@] Failed to parse comid registry JSON", MindustryYZF.name, e);
            return false;
        }
    }

    private synchronized void save(){
        Jval root = buildSnapshot();
        try{
            if(sqlStore != null){
                for(ObjectMap.Entry<String, Long> e : uuidToComid){
                    sqlStore.upsertComid(e.key, e.value);
                }
            }else if(databaseRegistry != null && databaseRegistry.has(databaseRegistry.defaultId())){
                databaseRegistry.set(databaseRegistry.defaultId(), databaseCategory, databaseKey, root.toString(Jval.Jformat.plain));
            }else if(allowLegacyFileFallback){
                saveLegacyFile(root);
            }
        }catch(Exception e){
            if(allowLegacyFileFallback){
                try{
                    saveLegacyFile(root);
                }catch(Exception fallback){
                    Log.err("[@] Failed to save comid registry to local database", MindustryYZF.name, e);
                    Log.err("[@] Failed to save comid registry to legacy file", MindustryYZF.name, fallback);
                }
            }else{
                Log.err("[@] Failed to save comid registry to configured database backend", MindustryYZF.name, e);
            }
        }
    }

    private Jval buildSnapshot(){
        Jval root = Jval.newObject();
        Jval entries = Jval.newArray();
        for(ObjectMap.Entry<String, Long> e : uuidToComid){
            Jval entry = Jval.newObject();
            entry.put("uuid", e.key);
            entry.put("comid", e.value);
            entries.add(entry);
        }
        root.put("entries", entries);
        root.put("currentDigits", currentDigits);
        return root;
    }

    private void saveLegacyFile(Jval root) throws Exception{
        if(legacyFile == null) return;
        File parent = legacyFile.getParentFile();
        if(parent != null && !parent.exists()){
            parent.mkdirs();
        }
        try(Writer writer = new OutputStreamWriter(new FileOutputStream(legacyFile), StandardCharsets.UTF_8)){
            writer.write(root.toString(Jval.Jformat.formatted));
        }
    }
}
