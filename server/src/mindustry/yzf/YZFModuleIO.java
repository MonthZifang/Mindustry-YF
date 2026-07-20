package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;

public final class YZFModuleIO{
    private YZFModuleIO(){
    }

    public static void writeMeta(YZFModuleDefinition module){
        Fi file = module.metaFile;
        if(file.extension().equalsIgnoreCase("json")){
            file.writeString(toJson(module.meta));
        }else{
            file.writeString(toHjson(module.meta));
        }
    }

    private static String toHjson(YZFModuleMeta meta){
        StringBuilder out = new StringBuilder();
        out.append("name: ").append(meta.name).append('\n');
        out.append("id: ").append(meta.id).append('\n');
        out.append("author: ").append(meta.author).append('\n');
        out.append("description: \"").append(escape(meta.description)).append("\"\n");
        out.append("version: \"").append(escape(meta.version)).append("\"\n");
        out.append("main: \"").append(escape(meta.main)).append("\"\n");
        out.append("runtime: \"").append(escape(meta.runtime)).append("\"\n");
        out.append("memoryMin: \"").append(escape(meta.memoryMin)).append("\"\n");
        out.append("memoryMax: \"").append(escape(meta.memoryMax)).append("\"\n");
        out.append("enabled: ").append(meta.enabled ? "true" : "false").append('\n');
        out.append("hidden: ").append(meta.hidden ? "true" : "false").append('\n');
        out.append("requiresArgs: ").append(meta.requiresArgs ? "true" : "false").append('\n');
        out.append("category: \"").append(escape(meta.category)).append("\"\n");
        out.append("permission: \"").append(escape(meta.permission)).append("\"\n");
        writeArray(out, "tags", meta.tags);
        writeArray(out, "depends", meta.depends);
        writeArray(out, "softDepends", meta.softDepends);
        return out.toString();
    }

    private static String toJson(YZFModuleMeta meta){
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"name\": \"").append(escape(meta.name)).append("\",\n");
        out.append("  \"id\": \"").append(escape(meta.id)).append("\",\n");
        out.append("  \"author\": \"").append(escape(meta.author)).append("\",\n");
        out.append("  \"description\": \"").append(escape(meta.description)).append("\",\n");
        out.append("  \"version\": \"").append(escape(meta.version)).append("\",\n");
        out.append("  \"main\": \"").append(escape(meta.main)).append("\",\n");
        out.append("  \"runtime\": \"").append(escape(meta.runtime)).append("\",\n");
        out.append("  \"memoryMin\": \"").append(escape(meta.memoryMin)).append("\",\n");
        out.append("  \"memoryMax\": \"").append(escape(meta.memoryMax)).append("\",\n");
        out.append("  \"enabled\": ").append(meta.enabled ? "true" : "false").append(",\n");
        out.append("  \"hidden\": ").append(meta.hidden ? "true" : "false").append(",\n");
        out.append("  \"requiresArgs\": ").append(meta.requiresArgs ? "true" : "false").append(",\n");
        out.append("  \"category\": \"").append(escape(meta.category)).append("\",\n");
        out.append("  \"permission\": \"").append(escape(meta.permission)).append("\",\n");
        writeJsonArray(out, "tags", meta.tags);
        out.append(",\n");
        writeJsonArray(out, "depends", meta.depends);
        out.append(",\n");
        writeJsonArray(out, "softDepends", meta.softDepends);
        out.append("\n}\n");
        return out.toString();
    }

    private static void writeArray(StringBuilder out, String name, Seq<String> values){
        out.append(name).append(": [");
        for(int i = 0; i < values.size; i++){
            if(i > 0) out.append(", ");
            out.append(values.get(i));
        }
        out.append("]\n");
    }

    private static void writeJsonArray(StringBuilder out, String name, Seq<String> values){
        out.append("  \"").append(name).append("\": [");
        for(int i = 0; i < values.size; i++){
            if(i > 0) out.append(", ");
            out.append("\"").append(escape(values.get(i))).append("\"");
        }
        out.append("]");
    }

    private static String escape(String text){
        return (text == null ? "" : text).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
