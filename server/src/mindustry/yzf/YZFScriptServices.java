package mindustry.yzf;

import arc.util.serialization.Jval;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Locale;

public final class YZFScriptServices{
    private final YZFContext context;
    private final YZFRemoteFramework remote;

    public YZFScriptServices(YZFContext context){
        this.context = context;
        this.remote = new YZFRemoteFramework(context);
    }

    public String httpGet(String serviceId, String path) throws Exception{
        YZFRemoteRequest request = new YZFRemoteRequest();
        request.serviceId = serviceId;
        request.method = "GET";
        request.path = path;
        return remote.execute(request);
    }

    public String httpPostJson(String serviceId, String path, String body) throws Exception{
        YZFRemoteRequest request = new YZFRemoteRequest();
        request.serviceId = serviceId;
        request.method = "POST";
        request.path = path;
        request.body = body;
        request.headers.put("Content-Type", "application/json; charset=utf-8");
        return remote.execute(request);
    }

    public void redisSet(String serviceId, String key, String value){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        client.set(key, value);
    }

    public String redisGet(String serviceId, String key){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        return client.get(key);
    }

    public long redisIncrement(String serviceId, String key){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        return client.increment(key);
    }

    public void redisDelete(String serviceId, String key){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        client.delete(key);
    }

    public void redisHashSet(String serviceId, String key, String field, String value){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        client.hashSet(key, field, value);
    }

    public String redisHashGet(String serviceId, String key, String field){
        context.metrics.redisCalls.incrementAndGet();
        YZFCacheClient client = context.services.registry().getAs(serviceId, YZFCacheClient.class);
        if(client == null) throw new IllegalStateException("找不到 Redis 服务: " + serviceId);
        return client.hashGet(key, field);
    }

    public String sqlQueryFirstCell(String serviceId, String sql) throws Exception{
        context.metrics.sqlCalls.incrementAndGet();
        YZFSqlClient client = context.services.registry().getAs(serviceId, YZFSqlClient.class);
        if(client == null) throw new IllegalStateException("找不到 SQL 服务: " + serviceId);
        try(Connection connection = client.dataSource().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)){
            if(result.next()){
                return result.getString(1);
            }
            return null;
        }
    }

    public int sqlExecute(String serviceId, String sql) throws Exception{
        context.metrics.sqlCalls.incrementAndGet();
        YZFSqlClient client = context.services.registry().getAs(serviceId, YZFSqlClient.class);
        if(client == null) throw new IllegalStateException("找不到 SQL 服务: " + serviceId);
        try(Connection connection = client.dataSource().getConnection(); Statement statement = connection.createStatement()){
            return statement.executeUpdate(sql);
        }
    }

    public String sqlQueryJson(String serviceId, String sql) throws Exception{
        context.metrics.sqlCalls.incrementAndGet();
        YZFSqlClient client = context.services.registry().getAs(serviceId, YZFSqlClient.class);
        if(client == null) throw new IllegalStateException("找不到 SQL 服务: " + serviceId);
        try(Connection connection = client.dataSource().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)){
            Jval rows = Jval.newArray();
            ResultSetMetaData meta = result.getMetaData();
            while(result.next()){
                Jval row = Jval.newObject();
                for(int i = 1; i <= meta.getColumnCount(); i++){
                    Object value = result.getObject(i);
                    row.put(meta.getColumnLabel(i), value == null ? null : String.valueOf(value));
                }
                rows.add(row);
            }
            return rows.toString(Jval.Jformat.plain);
        }
    }

    public void minioPutText(String serviceId, String objectName, String text) throws Exception{
        YZFObjectStorageClient client = context.services.registry().getAs(serviceId, YZFObjectStorageClient.class);
        if(client == null) throw new IllegalStateException("找不到 MinIO 服务: " + serviceId);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        client.putObject(objectName, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
    }

    public String serviceCall(String serviceId, String action, String... args) throws Exception{
        context.metrics.serviceCalls.incrementAndGet();
        YZFServiceClient client = context.services.registry().get(serviceId);
        if(client == null) throw new IllegalStateException("找不到服务: " + serviceId);
        String op = action == null ? "" : action.toLowerCase(Locale.ROOT);

        if(client instanceof YZFRemoteClient remoteClient){
            if(op.equals("get")) return remoteClient.get(args.length > 0 ? args[0] : "/");
            if(op.equals("postjson")) return remoteClient.postJson(args.length > 0 ? args[0] : "/", args.length > 1 ? args[1] : "");
        }
        if(client instanceof YZFCacheClient cacheClient){
            if(op.equals("get")) return cacheClient.get(args[0]);
            if(op.equals("set")){
                cacheClient.set(args[0], args.length > 1 ? args[1] : "");
                return "OK";
            }
            if(op.equals("delete")){
                cacheClient.delete(args[0]);
                return "OK";
            }
            if(op.equals("incr")) return String.valueOf(cacheClient.increment(args[0]));
            if(op.equals("hget")) return cacheClient.hashGet(args[0], args[1]);
            if(op.equals("hset")){
                cacheClient.hashSet(args[0], args[1], args.length > 2 ? args[2] : "");
                return "OK";
            }
        }
        if(client instanceof YZFSqlClient){
            if(op.equals("queryfirstcell")) return sqlQueryFirstCell(serviceId, args[0]);
            if(op.equals("queryjson")) return sqlQueryJson(serviceId, args[0]);
            if(op.equals("execute")) return String.valueOf(sqlExecute(serviceId, args[0]));
        }
        if(client instanceof YZFObjectStorageClient){
            if(op.equals("puttext")){
                minioPutText(serviceId, args[0], args.length > 1 ? args[1] : "");
                return "OK";
            }
        }
        throw new IllegalArgumentException("不支持的服务操作: " + serviceId + "/" + action);
    }
}
