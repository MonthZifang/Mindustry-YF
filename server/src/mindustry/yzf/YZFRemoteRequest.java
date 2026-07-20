package mindustry.yzf;

import arc.struct.ObjectMap;

public final class YZFRemoteRequest{
    public String serviceId;
    public String method = "GET";
    public String path = "/";
    public String body = "";
    public final ObjectMap<String, String> headers = new ObjectMap<>();

    public String header(String key, String defaultValue){
        String value = headers.get(key);
        return value == null ? defaultValue : value;
    }
}
