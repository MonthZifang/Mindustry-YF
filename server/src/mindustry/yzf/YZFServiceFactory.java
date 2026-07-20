package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;

import java.util.Locale;

public final class YZFServiceFactory{
    private static final ObjectMap<String, YZFServiceBuilder> builders = new ObjectMap<>();
    private static YZFDriverRegistry currentDriverRegistry;

    private YZFServiceFactory(){
    }

    public static synchronized void register(String type, YZFServiceBuilder builder){
        if(YZFText.blank(type)) throw new IllegalArgumentException("service type cannot be blank");
        if(builder == null) throw new IllegalArgumentException("service builder cannot be null");
        builders.put(normalize(type), builder);
    }

    public static synchronized boolean has(String type){
        if(YZFText.blank(type)) return false;
        return builders.containsKey(normalize(type));
    }

    public static synchronized Seq<String> types(){
        return builders.keys().toSeq();
    }

    public static synchronized YZFServiceClient create(YZFServiceConfig config, YZFDriverRegistry driverRegistry){
        ensureDefaults(driverRegistry);
        if(config == null) throw new IllegalArgumentException("service config cannot be null");

        String type = normalize(config.type);
        if(YZFText.blank(type)){
            throw new IllegalArgumentException("service type cannot be blank");
        }

        YZFServiceBuilder builder = builders.get(type);
        if(builder == null){
            throw new IllegalArgumentException("unsupported service type: " + config.type + " (registered: " + types() + ")");
        }
        return builder.create(config);
    }

    private static void ensureDefaults(YZFDriverRegistry driverRegistry){
        if(!builders.isEmpty() && currentDriverRegistry == driverRegistry) return;
        builders.clear();
        currentDriverRegistry = driverRegistry;

        register("minio", config -> new YZFMinioClient(config, driverRegistry));
        register("mysql", config -> new YZFHikariSqlClient(config, "com.mysql.cj.jdbc.Driver", driverRegistry));
        register("mariadb", config -> new YZFHikariSqlClient(config, "org.mariadb.jdbc.Driver", driverRegistry));
        register("postgresql", config -> new YZFHikariSqlClient(config, "org.postgresql.Driver", driverRegistry));
        register("postgres", config -> new YZFHikariSqlClient(config, "org.postgresql.Driver", driverRegistry));
        register("sqlite", config -> new YZFSqliteClient(config));
        register("redis", config -> new YZFRedisClient(config, driverRegistry));
        register("remotehttp", config -> new YZFRemoteHttpClient(config));
    }

    private static String normalize(String type){
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }
}
