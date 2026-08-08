package mindustry.yzf;

import arc.struct.ObjectMap;
import mindustry.events.*;
import mindustry.game.EventType;

public final class YZFEventRegistry{
    private static final ObjectMap<String, Class<?>> eventTypes = new ObjectMap<>();

    static{
        for(Class<?> type : EventType.class.getClasses()){
            register(type.getSimpleName(), type);
        }
        register("PlayerJoin", EventType.PlayerJoin.class);
        register("PlayerLeave", EventType.PlayerLeave.class);
        register("PlayEvent", EventType.PlayEvent.class);
        register("ResetEvent", EventType.ResetEvent.class);
        register("ServerLoadEvent", EventType.ServerLoadEvent.class);
        register("WorldLoadEvent", EventType.WorldLoadEvent.class);
        register("GameOverEvent", EventType.GameOverEvent.class);
        register("PlayerChatEvent", EventType.PlayerChatEvent.class);

        // 第一阶段自定义事件（mindustry.events 包，非 EventType 内部类）
        register("SendPacketEvent", SendPacketEvent.class);
        register("ReceivePacketEvent", ReceivePacketEvent.class);
        register("PlayerTeamChangedEvent", PlayerTeamChangedEvent.class);
        register("HealthChangedEvent", HealthChangedEvent.class);
        register("LogicAssembledEvent", LogicAssembledEvent.class);
    }

    private YZFEventRegistry(){
    }

    public static Class<?> find(String eventName){
        if(YZFText.blank(eventName)) return null;
        return eventTypes.get(normalize(eventName));
    }

    private static void register(String name, Class<?> type){
        eventTypes.put(normalize(name), type);
    }

    private static String normalize(String value){
        return value.trim().toLowerCase();
    }
}
