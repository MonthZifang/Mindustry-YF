package mindustry.yzf;

import arc.func.Cons;

public final class YZFEventBinding{
    public final String eventName;
    public final Class<?> eventType;
    public final Cons<Object> handler;

    public YZFEventBinding(String eventName, Class<?> eventType, Cons<Object> handler){
        this.eventName = eventName;
        this.eventType = eventType;
        this.handler = handler;
    }
}
