package mindustry.yzf;

public interface YZFServiceClient{
    YZFServiceConfig config();

    String summary();

    void start() throws Exception;

    void stop();

    boolean healthy();

    default String healthDetails(){
        return healthy() ? "正常" : "异常";
    }
}
