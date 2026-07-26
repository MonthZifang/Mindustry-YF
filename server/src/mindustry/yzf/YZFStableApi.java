package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.gen.Groups;

/**
 * Stable, configuration-backed API names for plugins and diagnostic scripts.
 * The HJSON file selects aliases from this fixed allow-list; it never exposes
 * arbitrary reflection targets.
 */
public final class YZFStableApi{
    private static final ObjectMap<String, String> aliases = new ObjectMap<>();
    private static final Seq<Entry> entries = new Seq<>();
    private static boolean enabled = true;
    private static Fi configFile;

    private YZFStableApi(){
    }

    public static synchronized void load(YZFPaths paths){
        configFile = paths == null ? null : paths.stableApiFile;
        aliases.clear();
        entries.clear();
        enabled = true;
        if(configFile == null || !configFile.exists()) return;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(configFile));
            enabled = root.getBool("enabled", true);
            Jval configured = root.get("interfaces");
            if(configured != null && configured.isArray()){
                for(Jval value : configured.asArray()){
                    String id = value.getString("id", "").trim();
                    String target = value.getString("target", id).trim();
                    if(id.isEmpty() || !isSupported(target)){
                        Log.warn("[MindustryYZF] Ignoring unknown stable API entry: @ -> @", id, target);
                        continue;
                    }
                    aliases.put(id, target);
                    entries.add(new Entry(id, target, value.getString("description", defaultDescription(target))));
                }
            }
            if(entries.isEmpty()) installDefaults();
        }catch(Exception error){
            YZFErrorLog.high("stable-api", "Invalid stable-api.hjson; using built-in API names", error);
            installDefaults();
        }
        writeDebugScript(paths == null ? null : paths.stableApiDebugFile);
    }

    public static synchronized Object call(String id){
        if(!enabled || id == null) return null;
        String target = aliases.get(id.trim(), id.trim());
        return switch(target){
            case "server.actualTps" -> YZFServerMetrics.actualTps();
            case "server.tpsLimit" -> YZFServerMetrics.tpsLimit();
            case "server.status" -> YZFStatusUi.statusJson();
            case "server.openApiManifest" -> YZFOpenApiRegistry.manifestJson();
            case "server.openApiSummary" -> YZFOpenApiRegistry.summaryJson();
            case "server.playerCount" -> Groups.player == null ? 0 : Groups.player.size();
            default -> null;
        };
    }

    public static synchronized String manifestJson(){
        Jval root = Jval.newObject();
        root.put("ok", true);
        root.put("enabled", enabled);
        root.put("configPath", configFile == null ? "" : configFile.absolutePath());
        Jval values = Jval.newArray();
        for(Entry entry : entries){
            Jval value = Jval.newObject();
            value.put("id", entry.id);
            value.put("target", entry.target);
            value.put("description", entry.description);
            values.add(value);
        }
        root.put("interfaces", values);
        return root.toString(Jval.Jformat.plain);
    }

    private static void installDefaults(){
        add("server.actualTps", "server.actualTps", "Measured server updates per second.");
        add("server.tpsLimit", "server.tpsLimit", "Configured server TPS limit.");
        add("server.status", "server.status", "Current structured server status JSON.");
        add("server.openApiManifest", "server.openApiManifest", "YZF capability manifest JSON.");
        add("server.openApiSummary", "server.openApiSummary", "YZF capability summary JSON.");
        add("server.playerCount", "server.playerCount", "Connected player count.");
    }

    private static void add(String id, String target, String description){
        aliases.put(id, target);
        entries.add(new Entry(id, target, description));
    }

    private static boolean isSupported(String target){
        return "server.actualTps".equals(target) || "server.tpsLimit".equals(target)
            || "server.status".equals(target) || "server.openApiManifest".equals(target)
            || "server.openApiSummary".equals(target) || "server.playerCount".equals(target);
    }

    private static String defaultDescription(String target){
        return switch(target){
            case "server.actualTps" -> "Measured server updates per second.";
            case "server.tpsLimit" -> "Configured server TPS limit.";
            case "server.status" -> "Current structured server status JSON.";
            case "server.openApiManifest" -> "YZF capability manifest JSON.";
            case "server.openApiSummary" -> "YZF capability summary JSON.";
            case "server.playerCount" -> "Connected player count.";
            default -> "";
        };
    }

    private static void writeDebugScript(Fi file){
        if(file == null) return;
        try{
            file.writeString("// Generated from config/stable-api.hjson. Do not edit; it is regenerated at server start.\n"
                + "// Usage from a YZF JavaScript module: stableApi.call('server.actualTps')\n"
                + "var stableApi = {\n"
                + "  call: function(id){ return yzf.stableApi(String(id)); },\n"
                + "  manifest: function(){ return String(yzf.stableApiManifest()); }\n"
                + "};\n");
        }catch(Exception error){
            YZFErrorLog.high("stable-api", "Failed to generate stable API debug script", error);
        }
    }

    private static final class Entry{
        final String id, target, description;
        Entry(String id, String target, String description){ this.id = id; this.target = target; this.description = description; }
    }
}
