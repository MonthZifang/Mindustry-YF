package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

public final class YZFModuleLoader{
    private static final Seq<String> scriptExtensions = Seq.with("js", "mjs", "kt", "kts", "yfs", "java", "node", "jar");

    private YZFModuleLoader(){
    }

    public static Seq<String> supportedScriptExtensions(){
        return scriptExtensions.copy();
    }

    public static Seq<String> supportedRuntimes(){
        return Seq.with("js", "node", "java", "kt", "kts");
    }

    public static YZFModuleDefinition loadModule(Fi root){
        Fi metaFile = resolveMetaFile(root);
        if(metaFile == null) return null;

        YZFModuleMeta meta = readMeta(metaFile);
        if(YZFText.blank(meta.id)) meta.id = root.name();
        if(YZFText.blank(meta.name)) meta.name = root.name();
        if(YZFText.blank(meta.author)) meta.author = root.parent() == null ? "unknown" : root.parent().name();
        if(YZFText.blank(meta.main)) meta.main = "scripts/main.js";
        if(YZFText.blank(meta.runtime)) meta.runtime = "js";

        Fi scriptsDir = root.child("scripts");
        Fi dataDir = root.child("data");
        Fi cacheDir = root.child("cache");
        Fi mainScript = root.child(meta.main);

        YZFModuleDefinition definition = new YZFModuleDefinition(meta, root, metaFile, scriptsDir, dataDir, cacheDir, mainScript);
        collectScripts(scriptsDir, definition.scripts);
        if(definition.hasMain() && !definition.scripts.contains(mainScript)){
            definition.scripts.insert(0, mainScript);
        }
        return definition;
    }

    private static Fi resolveMetaFile(Fi root){
        Fi hjson = root.child("module.hjson");
        if(hjson.exists() && !hjson.isDirectory()) return hjson;

        Fi json = root.child("module.json");
        if(json.exists() && !json.isDirectory()) return json;

        return null;
    }

    private static YZFModuleMeta readMeta(Fi file){
        YZFModuleMeta meta = new YZFModuleMeta();
        try{
            Jval data = Jval.read(YZFText.readTextSmart(file));
            meta.id = data.getString("id", meta.id);
            meta.name = data.getString("name", meta.name);
            meta.author = data.getString("author", meta.author);
            meta.description = data.getString("description", meta.description);
            meta.version = data.getString("version", meta.version);
            meta.main = data.getString("main", meta.main);
            meta.runtime = data.getString("runtime", meta.runtime);
            meta.enabled = data.getBool("enabled", meta.enabled);
            meta.hidden = data.getBool("hidden", meta.hidden);
            meta.requiresArgs = data.getBool("requiresArgs", meta.requiresArgs);
            meta.category = data.getString("category", meta.category);
            meta.permission = data.getString("permission", meta.permission);
            if(data.has("tags") && data.get("tags").isArray()){
                for(Jval child : data.get("tags").asArray()){
                    if(child.isString()) meta.tags.add(child.asString());
                }
            }
            if(data.has("depends") && data.get("depends").isArray()){
                for(Jval child : data.get("depends").asArray()){
                    if(child.isString()) meta.depends.add(child.asString());
                }
            }
            if(data.has("softDepends") && data.get("softDepends").isArray()){
                for(Jval child : data.get("softDepends").asArray()){
                    if(child.isString()) meta.softDepends.add(child.asString());
                }
            }
            if(data.has("jvmArgs") && data.get("jvmArgs").isArray()){
                for(Jval child : data.get("jvmArgs").asArray()){
                    if(child.isString()) meta.jvmArgs.add(child.asString());
                }
            }
            meta.memoryMin = data.getString("memoryMin", data.getString("minHeap", ""));
            meta.memoryMax = data.getString("memoryMax", data.getString("maxHeap", ""));
            if(data.has("programArgs") && data.get("programArgs").isArray()){
                for(Jval child : data.get("programArgs").asArray()){
                    if(child.isString()) meta.programArgs.add(child.asString());
                }
            }
            meta.loadType = data.getString("loadType", meta.loadType);
        }catch(Throwable t){
            Log.err("[@] Failed to read module metadata: @", MindustryYZF.name, file.absolutePath(), t);
        }
        return meta;
    }

    private static void collectScripts(Fi root, Seq<Fi> out){
        if(root == null || !root.exists()) return;
        if(root.isDirectory()){
            for(Fi child : root.list()){
                collectScripts(child, out);
            }
            return;
        }

        if(scriptExtensions.contains(root.extension().toLowerCase())){
            out.add(root);
        }
    }
}
