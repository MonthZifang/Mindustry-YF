package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Local JSON database laid out as:
 * databases/<dbId>/<category>/<key>.json
 *
 * Category can be a path-like string such as "system/comid".
 * This keeps the structure human-readable while still supporting deep nesting.
 */
public final class YZFLocalJsonDatabaseClient implements YZFDatabaseClient{
    private static final String legacyFileSuffix = ".json";

    private final YZFDatabaseDefinition definition;
    private final Fi rootDir;
    private final Fi legacyFile;
    private final ObjectMap<String, ObjectMap<String, String>> cache = new ObjectMap<>();

    public YZFLocalJsonDatabaseClient(YZFDatabaseDefinition definition, Fi rootDir){
        this.definition = definition;
        this.rootDir = rootDir;
        this.legacyFile = rootDir.sibling(rootDir.name() + legacyFileSuffix);
    }

    @Override
    public YZFDatabaseDefinition definition(){
        return definition;
    }

    @Override
    public String summary(){
        return "Local JSON -> " + rootDir.absolutePath();
    }

    @Override
    public void start(){
        loadAll();
    }

    @Override
    public void stop(){
        saveAll();
    }

    @Override
    public boolean healthy(){
        return rootDir != null;
    }

    @Override
    public String healthDetails(){
        return rootDir == null ? "no directory" : rootDir.absolutePath();
    }

    @Override
    public synchronized String listCategories(){
        Seq<String> categories = new Seq<>();
        collectCategories(rootDir.file(), "", categories);
        Jval array = Jval.newArray();
        for(String category : categories){
            array.add(category);
        }
        return array.toString(Jval.Jformat.plain);
    }

    @Override
    public synchronized String listKeys(String category){
        ObjectMap<String, String> data = loadCategory(category);
        Jval array = Jval.newArray();
        for(String key : data.keys()){
            array.add(key);
        }
        return array.toString(Jval.Jformat.plain);
    }

    @Override
    public synchronized String get(String category, String key){
        return loadCategory(category).get(key);
    }

    @Override
    public synchronized void set(String category, String key, String valueJson){
        ObjectMap<String, String> data = loadCategory(category);
        if(valueJson == null){
            data.remove(key);
        }else{
            data.put(key, normalizeJson(valueJson));
        }
        saveEntry(category, key, data.get(key));
    }

    @Override
    public synchronized boolean remove(String category, String key){
        ObjectMap<String, String> data = loadCategory(category);
        String removed = data.remove(key);
        if(removed == null) return false;
        File file = entryFile(category, key);
        if(file.exists()){
            file.delete();
        }
        return true;
    }

    @Override
    public synchronized String dumpJson(){
        Jval root = Jval.newObject();
        root.put("id", definition.id);
        root.put("name", definition.name);
        root.put("type", definition.type);

        Jval categories = Jval.newObject();
        Seq<String> categoryNames = new Seq<>();
        collectCategories(rootDir.file(), "", categoryNames);
        for(String category : categoryNames){
            Jval categoryObj = Jval.newObject();
            ObjectMap<String, String> data = loadCategory(category);
            for(ObjectMap.Entry<String, String> entry : data){
                categoryObj.put(entry.key, parseValue(entry.value));
            }
            categories.put(category, categoryObj);
        }
        root.put("categories", categories);
        return root.toString(Jval.Jformat.plain);
    }

    @Override
    public synchronized void importJson(String json){
        cache.clear();
        clearDirectory(rootDir.file());
        if(json == null || json.trim().isEmpty()){
            return;
        }
        Jval root = Jval.read(json);
        if(root != null && root.isObject()){
            Jval categories = root.has("categories") ? root.get("categories") : root;
            if(categories != null && categories.isObject()){
                for(var entry : categories.asObject()){
                    String category = entry.key;
                    if(entry.value == null || !entry.value.isObject()) continue;
                    for(var kv : entry.value.asObject()){
                        saveEntry(category, kv.key, kv.value == null ? null : kv.value.toString(Jval.Jformat.plain));
                    }
                }
            }
        }
    }

    private void loadAll(){
        cache.clear();
        if(loadFromDirectory()){
            return;
        }
        if(loadFromLegacyFile()){
            saveAll();
        }
    }

    private boolean loadFromDirectory(){
        if(rootDir == null || !rootDir.exists()) return false;
        boolean found = false;
        loadDirectoryRecursive(rootDir.file(), "", found);
        return !cache.isEmpty();
    }

    private void loadDirectoryRecursive(File dir, String categoryPrefix, boolean found){
        File[] files = dir.listFiles();
        if(files == null) return;
        for(File file : files){
            if(file.isDirectory()){
                String next = categoryPrefix.isEmpty() ? decodeSegment(file.getName()) : categoryPrefix + "/" + decodeSegment(file.getName());
                loadDirectoryRecursive(file, next, true);
            }else if(file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(legacyFileSuffix)){
                String key = decodeSegment(stripSuffix(file.getName(), legacyFileSuffix));
                ObjectMap<String, String> data = cache.get(categoryPrefix);
                if(data == null){
                    data = new ObjectMap<>();
                    cache.put(categoryPrefix, data);
                }
                try{
                    String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    data.put(key, normalizeJson(raw));
                }catch(Exception e){
                    Log.err("[@] Failed to load local db entry @/@", MindustryYZF.name, categoryPrefix, key, e);
                }
            }
        }
    }

    private boolean loadFromLegacyFile(){
        if(legacyFile == null || !legacyFile.exists()) return false;
        try{
            Jval root = Jval.read(legacyFile.readString());
            if(root == null || !root.isObject()) return false;
            Jval categories = root.has("categories") ? root.get("categories") : root;
            if(categories != null && categories.isObject()){
                for(var entry : categories.asObject()){
                    if(entry.value == null || !entry.value.isObject()) continue;
                    String category = entry.key;
                    for(var kv : entry.value.asObject()){
                        saveEntry(category, kv.key, kv.value == null ? null : kv.value.toString(Jval.Jformat.plain));
                    }
                }
            }
            return true;
        }catch(Exception e){
            Log.err("[@] Failed to load legacy local database file", MindustryYZF.name, e);
            return false;
        }
    }

    private void saveAll(){
        for(ObjectMap.Entry<String, ObjectMap<String, String>> category : cache){
            for(ObjectMap.Entry<String, String> entry : category.value){
                saveEntry(category.key, entry.key, entry.value);
            }
        }
    }

    private ObjectMap<String, String> loadCategory(String category){
        String normalizedCategory = normalizeCategory(category);
        ObjectMap<String, String> data = cache.get(normalizedCategory);
        if(data != null) return data;
        data = new ObjectMap<>();
        File dir = categoryDir(normalizedCategory);
        if(dir.exists() && dir.isDirectory()){
            File[] files = dir.listFiles();
            if(files != null){
                for(File file : files){
                    if(!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(legacyFileSuffix)) continue;
                    String key = decodeSegment(stripSuffix(file.getName(), legacyFileSuffix));
                    try{
                        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                        data.put(key, normalizeJson(raw));
                    }catch(Exception e){
                        Log.err("[@] Failed to load local db entry @/@", MindustryYZF.name, normalizedCategory, key, e);
                    }
                }
            }
        }
        cache.put(normalizedCategory, data);
        return data;
    }

    private void saveEntry(String category, String key, String valueJson){
        String normalizedCategory = normalizeCategory(category);
        String normalizedKey = normalizeKey(key);
        ObjectMap<String, String> data = cache.get(normalizedCategory);
        if(data == null){
            data = new ObjectMap<>();
            cache.put(normalizedCategory, data);
        }

        if(valueJson == null){
            data.remove(normalizedKey);
        }else{
            data.put(normalizedKey, normalizeJson(valueJson));
        }

        File file = entryFile(normalizedCategory, normalizedKey);
        if(valueJson == null){
            if(file.exists()) file.delete();
            return;
        }
        file.getParentFile().mkdirs();
        try(Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)){
            writer.write(data.get(normalizedKey));
        }catch(Exception e){
            Log.err("[@] Failed to save local db entry @/@", MindustryYZF.name, normalizedCategory, normalizedKey, e);
        }
    }

    private File categoryDir(String category){
        File dir = rootDir.file();
        if(category == null || category.isBlank()) return dir;
        String[] parts = category.split("/");
        for(String part : parts){
            if(part.isBlank()) continue;
            dir = new File(dir, encodeSegment(part));
        }
        return dir;
    }

    private File entryFile(String category, String key){
        return new File(categoryDir(category), encodeSegment(key) + legacyFileSuffix);
    }

    private String normalizeCategory(String category){
        if(category == null) return "";
        String value = category.trim().replace('\\', '/');
        while(value.startsWith("/")) value = value.substring(1);
        while(value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String normalizeKey(String key){
        return key == null ? "" : key.trim();
    }

    private String normalizeJson(String valueJson){
        String value = valueJson == null ? "" : valueJson.trim();
        if(value.isEmpty()) return "\"\"";
        try{
            return Jval.read(value).toString(Jval.Jformat.plain);
        }catch(Exception e){
            return Jval.read("\"" + escapeJson(value) + "\"").toString(Jval.Jformat.plain);
        }
    }

    private Jval parseValue(String raw){
        if(raw == null) return null;
        try{
            return Jval.read(raw);
        }catch(Exception e){
            return Jval.read("\"" + escapeJson(raw) + "\"");
        }
    }

    private void collectCategories(File dir, String prefix, Seq<String> output){
        File[] files = dir.listFiles();
        if(files == null) return;
        boolean hasJson = false;
        for(File file : files){
            if(file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(legacyFileSuffix)){
                hasJson = true;
            }
        }
        if(hasJson && !prefix.isEmpty() && !output.contains(prefix)){
            output.add(prefix);
        }
        for(File file : files){
            if(!file.isDirectory()) continue;
            String next = prefix.isEmpty() ? decodeSegment(file.getName()) : prefix + "/" + decodeSegment(file.getName());
            collectCategories(file, next, output);
        }
    }

    private void clearDirectory(File dir){
        if(dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if(files != null){
            for(File file : files){
                if(file.isDirectory()){
                    clearDirectory(file);
                }else{
                    file.delete();
                }
            }
        }
    }

    private String encodeSegment(String value){
        try{
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        }catch(Exception e){
            return value.replace("/", "_").replace("\\", "_");
        }
    }

    private String decodeSegment(String value){
        try{
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        }catch(Exception e){
            return value;
        }
    }

    private String stripSuffix(String value, String suffix){
        if(value != null && suffix != null && value.endsWith(suffix)){
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private String escapeJson(String text){
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
