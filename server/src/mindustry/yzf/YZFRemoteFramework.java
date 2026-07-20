package mindustry.yzf;

public final class YZFRemoteFramework{
    private final YZFContext context;

    public YZFRemoteFramework(YZFContext context){
        this.context = context;
    }

    public String execute(YZFRemoteRequest request) throws Exception{
        context.metrics.remoteCalls.incrementAndGet();
        YZFRemoteClient client = context.services.registry().getAs(request.serviceId, YZFRemoteClient.class);
        if(client == null){
            throw new IllegalStateException("找不到远程服务: " + request.serviceId);
        }
        return client.request(request);
    }
}
