package mindustry.yzf;

import arc.struct.Seq;

public final class YZFServiceConfig{
    public String id;
    public String type;
    public boolean enabled = true;
    public YZFClusterMode clusterMode = YZFClusterMode.standalone;
    public String sourcePath;
    public String endpoint;
    public String database;
    public String databaseFile;
    public String driverId;
    public String driverClassName;
    public String bucket;
    public String username;
    public String password;
    public String accessKey;
    public String secretKey;
    public String region;
    public String namespace;
    public int connectTimeoutMs = 10000;
    public int readTimeoutMs = 15000;
    public final Seq<String> nodes = new Seq<>();
    public final Seq<String> options = new Seq<>();

    public YZFServiceType typeEnum(){
        if(YZFText.blank(type)) return null;
        String normalized = type.trim();
        for(YZFServiceType value : YZFServiceType.values()){
            if(value.name().equalsIgnoreCase(normalized)){
                return value;
            }
        }
        return null;
    }

    public String option(String key, String defaultValue){
        if(key == null) return defaultValue;
        String prefix = key + "=";
        for(String option : options){
            if(option != null && option.startsWith(prefix)){
                return option.substring(prefix.length());
            }
        }
        return defaultValue;
    }
}
