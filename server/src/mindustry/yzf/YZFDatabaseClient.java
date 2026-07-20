package mindustry.yzf;

public interface YZFDatabaseClient{
    YZFDatabaseDefinition definition();

    String summary();

    void start() throws Exception;

    void stop();

    boolean healthy();

    String healthDetails();

    String listCategories() throws Exception;

    String listKeys(String category) throws Exception;

    String get(String category, String key) throws Exception;

    void set(String category, String key, String valueJson) throws Exception;

    boolean remove(String category, String key) throws Exception;

    String dumpJson() throws Exception;

    void importJson(String json) throws Exception;
}
