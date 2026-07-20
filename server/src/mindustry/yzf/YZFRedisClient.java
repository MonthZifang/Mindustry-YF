package mindustry.yzf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class YZFRedisClient implements YZFCacheClient{
    private final YZFServiceConfig config;
    private final YZFDriverRegistry driverRegistry;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final List<Object> pooledNodes = new ArrayList<>();
    private ClassLoader driverLoader;
    private Object pooled;
    private Object cluster;
    private Object sentinelPool;

    public YZFRedisClient(YZFServiceConfig config, YZFDriverRegistry driverRegistry){
        this.config = config;
        this.driverRegistry = driverRegistry;
    }

    @Override
    public YZFServiceConfig config(){
        return config;
    }

    @Override
    public String summary(){
        return "Redis " + config.clusterMode + " -> " + describeEndpoints();
    }

    @Override
    public synchronized void start(){
        stop();

        try{
            YZFDriverHandle handle = driverRegistry.require(config);
            driverLoader = handle.loader;
            List<String> nodes = resolveEndpoints();
            if(config.clusterMode == YZFClusterMode.sentinel){
                if(nodes.isEmpty()){
                    throw new IllegalStateException("Redis sentinel mode requires nodes: " + config.id);
                }
                sentinelPool = createSentinelPool(nodes);
                return;
            }

            if(config.clusterMode == YZFClusterMode.cluster){
                if(nodes.isEmpty()){
                    throw new IllegalStateException("Redis cluster mode requires nodes: " + config.id);
                }
                cluster = createCluster(nodes);
                return;
            }

            if(nodes.size() > 1 && (config.clusterMode == YZFClusterMode.loadbalance || config.clusterMode == YZFClusterMode.replication)){
                for(String node : nodes){
                    pooledNodes.add(createPooled(node));
                }
                return;
            }

            String endpoint = nodes.isEmpty() ? config.endpoint : nodes.get(0);
            pooled = createPooled(endpoint);
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Failed to start external Redis driver: " + config.id, e);
        }
    }

    @Override
    public synchronized void stop(){
        closeQuietly(cluster);
        closeQuietly(sentinelPool);
        closeQuietly(pooled);
        for(Object node : pooledNodes){
            closeQuietly(node);
        }
        cluster = null;
        sentinelPool = null;
        pooled = null;
        pooledNodes.clear();
        driverLoader = null;
    }

    @Override
    public synchronized boolean healthy(){
        try{
            if(cluster != null) return "PONG".equalsIgnoreCase(String.valueOf(invoke(cluster, "ping")));
            if(sentinelPool != null){
                Object jedis = invoke(sentinelPool, "getResource");
                try{
                    return "PONG".equalsIgnoreCase(String.valueOf(invoke(jedis, "ping")));
                }finally{
                    closeQuietly(jedis);
                }
            }
            if(!pooledNodes.isEmpty()){
                for(Object node : pooledNodes){
                    try{
                        if("PONG".equalsIgnoreCase(String.valueOf(invoke(node, "ping")))) return true;
                    }catch(Exception ignored){
                    }
                }
                return false;
            }
            if(pooled != null) return "PONG".equalsIgnoreCase(String.valueOf(invoke(pooled, "ping")));
        }catch(Exception ignored){
        }
        return false;
    }

    @Override
    public synchronized void set(String key, String value){
        try{
            if(cluster != null){
                invoke(cluster, "set", new Class<?>[]{String.class, String.class}, key, value);
                return;
            }
            if(sentinelPool != null){
                withSentinel(jedis -> invoke(jedis, "set", new Class<?>[]{String.class, String.class}, key, value));
                return;
            }
            invoke(selectPooled(), "set", new Class<?>[]{String.class, String.class}, key, value);
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis set failed: " + config.id, e);
        }
    }

    @Override
    public synchronized String get(String key){
        try{
            if(cluster != null) return String.valueOf(invoke(cluster, "get", new Class<?>[]{String.class}, key));
            if(sentinelPool != null){
                return String.valueOf(withSentinel(jedis -> invoke(jedis, "get", new Class<?>[]{String.class}, key)));
            }
            return String.valueOf(invoke(selectPooled(), "get", new Class<?>[]{String.class}, key));
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis get failed: " + config.id, e);
        }
    }

    @Override
    public synchronized void delete(String key){
        try{
            if(cluster != null){
                invoke(cluster, "del", new Class<?>[]{String.class}, key);
                return;
            }
            if(sentinelPool != null){
                withSentinel(jedis -> invoke(jedis, "del", new Class<?>[]{String.class}, key));
                return;
            }
            invoke(selectPooled(), "del", new Class<?>[]{String.class}, key);
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis delete failed: " + config.id, e);
        }
    }

    @Override
    public synchronized long increment(String key){
        try{
            if(cluster != null) return ((Number)invoke(cluster, "incr", new Class<?>[]{String.class}, key)).longValue();
            if(sentinelPool != null){
                return ((Number)withSentinel(jedis -> invoke(jedis, "incr", new Class<?>[]{String.class}, key))).longValue();
            }
            return ((Number)invoke(selectPooled(), "incr", new Class<?>[]{String.class}, key)).longValue();
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis increment failed: " + config.id, e);
        }
    }

    @Override
    public synchronized void hashSet(String key, String field, String value){
        try{
            if(cluster != null){
                invoke(cluster, "hset", new Class<?>[]{String.class, String.class, String.class}, key, field, value);
                return;
            }
            if(sentinelPool != null){
                withSentinel(jedis -> invoke(jedis, "hset", new Class<?>[]{String.class, String.class, String.class}, key, field, value));
                return;
            }
            invoke(selectPooled(), "hset", new Class<?>[]{String.class, String.class, String.class}, key, field, value);
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis hashSet failed: " + config.id, e);
        }
    }

    @Override
    public synchronized String hashGet(String key, String field){
        try{
            if(cluster != null) return String.valueOf(invoke(cluster, "hget", new Class<?>[]{String.class, String.class}, key, field));
            if(sentinelPool != null){
                return String.valueOf(withSentinel(jedis -> invoke(jedis, "hget", new Class<?>[]{String.class, String.class}, key, field)));
            }
            return String.valueOf(invoke(selectPooled(), "hget", new Class<?>[]{String.class, String.class}, key, field));
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("Redis hashGet failed: " + config.id, e);
        }
    }

    @Override
    public synchronized String healthDetails(){
        return describeEndpoints();
    }

    private Object createPooled(String endpoint) throws Exception{
        if(blank(endpoint)){
            endpoint = "127.0.0.1:6379";
        }
        Object hostAndPort = createHostAndPort(endpoint);
        return newInstance(load("redis.clients.jedis.JedisPooled"), new Class<?>[]{load("redis.clients.jedis.HostAndPort"), load("redis.clients.jedis.JedisClientConfig")}, hostAndPort, createClientConfig());
    }

    private Object createCluster(List<String> nodes) throws Exception{
        return newInstance(
            load("redis.clients.jedis.JedisCluster"),
            new Class<?>[]{Set.class, load("redis.clients.jedis.JedisClientConfig"), load("org.apache.commons.pool2.impl.GenericObjectPoolConfig")},
            toNodes(nodes),
            createClientConfig(),
            newInstance(load("org.apache.commons.pool2.impl.GenericObjectPoolConfig"), new Class<?>[0])
        );
    }

    private Object createSentinelPool(List<String> sentinels) throws Exception{
        String masterName = config.option("masterName", "mymaster");
        return newInstance(
            load("redis.clients.jedis.JedisSentinelPool"),
            new Class<?>[]{String.class, Set.class, load("org.apache.commons.pool2.impl.GenericObjectPoolConfig"), load("redis.clients.jedis.JedisClientConfig"), load("redis.clients.jedis.JedisClientConfig")},
            masterName,
            toNodes(sentinels),
            newInstance(load("org.apache.commons.pool2.impl.GenericObjectPoolConfig"), new Class<?>[0]),
            createClientConfig(),
            createClientConfig()
        );
    }

    private Object createClientConfig() throws Exception{
        Object builder = invokeStatic(load("redis.clients.jedis.DefaultJedisClientConfig"), "builder");
        builder = invoke(builder, "connectionTimeoutMillis", new Class<?>[]{int.class}, config.connectTimeoutMs);
        builder = invoke(builder, "socketTimeoutMillis", new Class<?>[]{int.class}, config.readTimeoutMs);

        if(!blank(config.username)){
            builder = invoke(builder, "user", new Class<?>[]{String.class}, config.username);
        }
        if(!blank(config.password)){
            builder = invoke(builder, "password", new Class<?>[]{String.class}, config.password);
        }

        String database = config.option("db", "0");
        try{
            builder = invoke(builder, "database", new Class<?>[]{int.class}, Integer.parseInt(database));
        }catch(Exception ignored){
            builder = invoke(builder, "database", new Class<?>[]{int.class}, 0);
        }
        return invoke(builder, "build");
    }

    private List<String> resolveEndpoints(){
        List<String> endpoints = new ArrayList<>();
        if(!config.nodes.isEmpty()){
            for(String node : config.nodes){
                if(!blank(node)) endpoints.add(node.trim());
            }
        }
        if(endpoints.isEmpty() && !blank(config.endpoint)){
            endpoints.add(config.endpoint.trim());
        }
        return endpoints;
    }

    private String describeEndpoints(){
        List<String> endpoints = resolveEndpoints();
        if(config.clusterMode == YZFClusterMode.sentinel){
            String masterName = config.option("masterName", "mymaster");
            return masterName + " via " + (endpoints.isEmpty() ? "<no-sentinels>" : String.join(", ", endpoints));
        }
        if(endpoints.isEmpty()){
            return "<unconfigured>";
        }
        if(endpoints.size() == 1){
            return endpoints.get(0);
        }
        return String.join(", ", endpoints) + " (routing)";
    }

    private Object selectPooled(){
        if(!pooledNodes.isEmpty()){
            int index = nextIndex() % pooledNodes.size();
            return pooledNodes.get(index);
        }
        if(pooled == null){
            throw new IllegalStateException("Redis client not started");
        }
        return pooled;
    }

    private int nextIndex(){
        int current = roundRobin.getAndIncrement();
        return current & Integer.MAX_VALUE;
    }

    private Object createHostAndPort(String endpoint) throws Exception{
        String value = endpoint.trim();
        String host = value;
        int port = 6379;

        if(value.startsWith("redis://") || value.startsWith("rediss://")){
            String withoutScheme = value.substring(value.indexOf("://") + 3);
            int atIndex = withoutScheme.lastIndexOf('@');
            if(atIndex >= 0){
                withoutScheme = withoutScheme.substring(atIndex + 1);
            }
            int slashIndex = withoutScheme.indexOf('/');
            if(slashIndex >= 0){
                withoutScheme = withoutScheme.substring(0, slashIndex);
            }
            host = withoutScheme;
            int colonIndex = withoutScheme.lastIndexOf(':');
            if(colonIndex > 0 && colonIndex < withoutScheme.length() - 1){
                host = withoutScheme.substring(0, colonIndex);
                port = parsePort(withoutScheme.substring(colonIndex + 1), 6379);
            }
        }else{
            int index = value.lastIndexOf(':');
            if(index > 0 && index < value.length() - 1){
                host = value.substring(0, index);
                port = parsePort(value.substring(index + 1), 6379);
            }
        }

        return newInstance(load("redis.clients.jedis.HostAndPort"), new Class<?>[]{String.class, int.class}, host, port);
    }

    private int parsePort(String raw, int fallback){
        try{
            return Integer.parseInt(raw);
        }catch(Exception ignored){
            return fallback;
        }
    }

    private Set<Object> toNodes(List<String> list) throws Exception{
        Set<Object> set = new LinkedHashSet<>();
        for(String value : list){
            set.add(createHostAndPort(value));
        }
        return set;
    }

    private boolean blank(String value){
        return value == null || value.trim().isEmpty();
    }

    private Object withSentinel(ReflectiveCall call) throws Exception{
        Object jedis = invoke(sentinelPool, "getResource");
        try{
            return call.run(jedis);
        }finally{
            closeQuietly(jedis);
        }
    }

    private Class<?> load(String typeName) throws Exception{
        return Class.forName(typeName, true, driverLoader);
    }

    private Object newInstance(Class<?> type, Class<?>[] parameterTypes, Object... args) throws Exception{
        Constructor<?> constructor = type.getConstructor(parameterTypes);
        return constructor.newInstance(args);
    }

    private Object invokeStatic(Class<?> type, String methodName) throws Exception{
        Method method = type.getMethod(methodName);
        return method.invoke(null);
    }

    private Object invoke(Object target, String methodName) throws Exception{
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception{
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private void closeQuietly(Object target){
        if(target == null) return;
        try{
            invoke(target, "close");
        }catch(Exception ignored){
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall{
        Object run(Object target) throws Exception;
    }
}
