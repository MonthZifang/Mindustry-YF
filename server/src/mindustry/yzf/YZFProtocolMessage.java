package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.util.serialization.Jval;

public final class YZFProtocolMessage{
    public String type;
    public final ObjectMap<String, String> fields = new ObjectMap<>();

    public String field(String key){
        return fields.get(key);
    }

    public String toJsonLine(){
        Jval root = Jval.newObject();
        root.put("type", type);
        Jval payload = Jval.newObject();
        for(ObjectMap.Entry<String, String> entry : fields){
            payload.put(entry.key, entry.value);
        }
        root.put("fields", payload);
        return root.toString(Jval.Jformat.plain);
    }

    public static YZFProtocolMessage parse(String line){
        Jval root = Jval.read(line);
        YZFProtocolMessage message = new YZFProtocolMessage();
        message.type = root.getString("type", "");
        Jval payload = root.get("fields");
        if(payload != null && payload.isObject()){
            for(var entry : payload.asObject()){
                message.fields.put(entry.key, entry.value == null ? "" : entry.value.asString());
            }
        }
        return message;
    }
}
