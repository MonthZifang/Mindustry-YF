package mindustry.yzf;

import arc.files.Fi;
import arc.util.serialization.Jval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

public final class YZFModuleConfigStore{
    private final YZFModuleDefinition module;
    private final Fi file;
    private Jval root;

    public YZFModuleConfigStore(YZFModuleDefinition module){
        this.module = module;
        // Runtime values are always stored in data/config/config.hjson.
        // The root config.hjson is an index/descriptor only.
        this.file = module.dataDir.child("config").child("config.hjson");
        migrateRootConfigIndex();
        load();
    }

    private void migrateRootConfigIndex(){
        Fi rootFile = module.root.child("config.hjson");
        Fi legacyFile = module.dataDir.child("config.hjson");
        if(!file.exists() && legacyFile.exists()){
            file.parent().mkdirs();
            file.writeString(YZFText.readTextSmart(legacyFile));
        }
        if(!rootFile.exists()){
            writeRootConfigIndex();
            return;
        }

        boolean portableIndex = false;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(rootFile));
            portableIndex = root.isObject()
                && root.getString("configFile", "").equals("data/config/config.hjson")
                && root.getString("configPath", "").equals("data/config/config.hjson");
        }catch(Exception ignored){
        }

        if(!file.exists() && !portableIndex){
            file.parent().mkdirs();
            file.writeString(YZFText.readTextSmart(rootFile));
        }
        if(!portableIndex) writeRootConfigIndex();
    }

    private void writeRootConfigIndex(){
        Jval index = Jval.newObject();
        index.put("configFile", "data/config/config.hjson");
        index.put("configPath", "data/config/config.hjson");
        index.put("configType", "runtime");
        index.put("note", "插件运行时配置");
        Jval tags = Jval.newArray();
        tags.add("runtime");
        index.put("tags", tags);
        Jval links = Jval.newArray();
        Jval link = Jval.newObject();
        link.put("path", "data/config/config.hjson");
        link.put("note", "插件运行时配置");
        Jval linkTags = Jval.newArray();
        linkTags.add("runtime");
        link.put("tags", linkTags);
        links.add(link);
        index.put("links", links);
        module.root.child("config.hjson").writeString(index.toString(Jval.Jformat.formatted));
    }

    public void load(){
        if(file.exists()){
            root = Jval.read(stripHashComments(YZFText.readTextSmart(file)));
            if(!root.isObject()) root = Jval.newObject();
        }else{
            root = Jval.newObject();
        }
    }

    /** Allows user-facing HJSON configs to use shell-style '#' comment lines. */
    private String stripHashComments(String text){
        if(text == null || text.isEmpty()) return "{}";
        StringBuilder result = new StringBuilder(text.length());
        Set<String> seenKeys = new HashSet<>();
        String[] lines = text.split("\\R", -1);
        for(String line : lines){
            String normalized = line;
            if(line.trim().startsWith("#")){
                // Some existing YZF config editors append the key to the comment line.
                // Recover the key portion when it is present; otherwise discard the comment.
                Matcher key = Pattern.compile("(?:^|[^A-Za-z0-9_])([A-Za-z][A-Za-z0-9_-]*)\\s*:").matcher(line);
                if(!key.find()) continue;
                normalized = line.substring(key.start(1));
            }

            // Normalize legacy/frontend duplicated values such as:
            // enabled: true true, edgeLeft: 100 4, or title: "x" "x".
            // The first value is the user's effective value; discard appended defaults.
            normalized = normalized.replaceFirst("^(\\s*[A-Za-z][A-Za-z0-9_-]*\\s*:\\s*)(true|false|-?\\d+(?:\\.\\d+)?)(?:\\s+(?:true|false|-?\\d+(?:\\.\\d+)?))+\\s*$", "$1$2");
            normalized = normalized.replaceFirst("^(\\s*[A-Za-z][A-Za-z0-9_-]*\\s*:\\s*)(\"(?:\\\\.|[^\"])*\")(?:\\s+\"(?:\\\\.|[^\"])*\")+\\s*$", "$1$2");

            // Also discard repeated key lines. Keep the first complete value so a
            // frontend default cannot overwrite the user's earlier value.
            Matcher keyMatcher = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*:").matcher(normalized);
            if(keyMatcher.find() && !seenKeys.add(keyMatcher.group(1))) continue;
            result.append(normalized).append('\n');
        }
        return result.toString();
    }

    public void save(){
        file.writeString(root.toString(Jval.Jformat.formatted));
    }

    public String getString(String key, String defaultValue){
        return root.getString(key, defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue){
        return root.getBool(key, defaultValue);
    }

    public int getInt(String key, int defaultValue){
        return root.getInt(key, defaultValue);
    }

    public void putString(String key, String value){
        root.put(key, value);
        save();
    }

    public void putBool(String key, boolean value){
        root.put(key, value);
        save();
    }

    public void putInt(String key, int value){
        root.put(key, value);
        save();
    }

    public String path(){
        return file.absolutePath();
    }
}
