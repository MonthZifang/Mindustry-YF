package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;

public final class YZFServiceRegistry{
    private final ObjectMap<String, YZFServiceClient> services = new ObjectMap<>();

    public void put(YZFServiceClient service){
        String id = service.config().id;
        YZFServiceClient previous = services.get(id);
        if(previous != null && previous != service){
            previous.stop();
        }
        services.put(id, service);
    }

    public YZFServiceClient get(String id){
        return services.get(id);
    }

    public <T extends YZFServiceClient> T getAs(String id, Class<T> type){
        YZFServiceClient client = services.get(id);
        return type.isInstance(client) ? type.cast(client) : null;
    }

    public Seq<YZFServiceClient> all(){
        return services.values().toSeq();
    }

    public Seq<String> ids(){
        return services.keys().toSeq();
    }

    public int healthyCount(){
        int count = 0;
        for(YZFServiceClient service : services.values()){
            if(service.healthy()) count++;
        }
        return count;
    }

    public void clear(){
        services.clear();
    }
}
