package mindustry.yzf;

public interface YZFCacheClient extends YZFServiceClient{
    void set(String key, String value);

    String get(String key);

    void delete(String key);

    long increment(String key);

    void hashSet(String key, String field, String value);

    String hashGet(String key, String field);
}
