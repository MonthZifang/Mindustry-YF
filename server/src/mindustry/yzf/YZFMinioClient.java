package mindustry.yzf;

import java.io.InputStream;
import java.lang.reflect.Method;

public final class YZFMinioClient implements YZFObjectStorageClient{
    private final YZFServiceConfig config;
    private final YZFDriverRegistry driverRegistry;
    private ClassLoader driverLoader;
    private Object client;

    public YZFMinioClient(YZFServiceConfig config, YZFDriverRegistry driverRegistry){
        this.config = config;
        this.driverRegistry = driverRegistry;
    }

    @Override
    public YZFServiceConfig config(){
        return config;
    }

    @Override
    public String summary(){
        return "MinIO " + config.clusterMode + " -> " + config.endpoint + " bucket=" + config.bucket;
    }

    @Override
    public void start() throws Exception{
        YZFDriverHandle handle = driverRegistry.require(config);
        driverLoader = handle.loader;

        Class<?> minioClientType = load("io.minio.MinioClient");
        Object builder = invokeStatic(minioClientType, "builder");
        builder = invoke(builder, "endpoint", new Class<?>[]{String.class}, config.endpoint);
        builder = invoke(builder, "credentials", new Class<?>[]{String.class, String.class}, config.accessKey, config.secretKey);
        client = invoke(builder, "build");

        if(!YZFText.blank(config.bucket) && !bucketExists()){
            invoke(client, "makeBucket", new Class<?>[]{load("io.minio.MakeBucketArgs")}, buildBucketArgs("io.minio.MakeBucketArgs", config.bucket));
        }
    }

    @Override
    public void stop(){
        closeClient();
        client = null;
        driverLoader = null;
    }

    @Override
    public boolean healthy(){
        return client != null;
    }

    @Override
    public void putObject(String objectName, InputStream stream, long size, String contentType) throws Exception{
        ensureStarted();
        Object argsBuilder = invokeStatic(load("io.minio.PutObjectArgs"), "builder");
        argsBuilder = invoke(argsBuilder, "bucket", new Class<?>[]{String.class}, config.bucket);
        argsBuilder = invoke(argsBuilder, "object", new Class<?>[]{String.class}, objectName);
        argsBuilder = invoke(argsBuilder, "stream", new Class<?>[]{InputStream.class, long.class, long.class}, stream, size, -1L);
        argsBuilder = invoke(argsBuilder, "contentType", new Class<?>[]{String.class}, contentType == null ? "application/octet-stream" : contentType);
        Object args = invoke(argsBuilder, "build");
        invoke(client, "putObject", new Class<?>[]{load("io.minio.PutObjectArgs")}, args);
    }

    @Override
    public boolean bucketExists() throws Exception{
        ensureStarted();
        Object args = buildBucketArgs("io.minio.BucketExistsArgs", config.bucket);
        Object result = invoke(client, "bucketExists", new Class<?>[]{load("io.minio.BucketExistsArgs")}, args);
        return result instanceof Boolean bool && bool;
    }

    private Object buildBucketArgs(String typeName, String bucket) throws Exception{
        Class<?> type = load(typeName);
        Object builder = invokeStatic(type, "builder");
        builder = invoke(builder, "bucket", new Class<?>[]{String.class}, bucket);
        return invoke(builder, "build");
    }

    private void ensureStarted(){
        if(client == null){
            throw new IllegalStateException("MinIO client not started: " + config.id);
        }
    }

    private Class<?> load(String typeName) throws Exception{
        return Class.forName(typeName, true, driverLoader);
    }

    private Object invokeStatic(Class<?> type, String methodName) throws Exception{
        Method method = type.getMethod(methodName);
        return method.invoke(null);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception{
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private Object invoke(Object target, String methodName) throws Exception{
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private void closeClient(){
        if(client == null) return;
        try{
            Method method = client.getClass().getMethod("close");
            method.invoke(client);
        }catch(NoSuchMethodException ignored){
        }catch(Exception ignored){
        }
    }
}
