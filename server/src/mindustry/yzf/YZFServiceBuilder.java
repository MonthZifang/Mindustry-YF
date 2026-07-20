package mindustry.yzf;

@FunctionalInterface
public interface YZFServiceBuilder{
    YZFServiceClient create(YZFServiceConfig config);
}
