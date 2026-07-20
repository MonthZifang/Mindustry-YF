package mindustry.events;

import arc.*;
import arc.util.*;
import mindustry.gen.*;

public class HealthChangedEvent{
    public static final HealthChangedEvent INSTANCE = new HealthChangedEvent();

    public Healthc entity;
    public @Nullable Entityc source;
    public boolean isSplash = false;
    public float amount;

    public static void fire(Healthc entity, float amount){
        INSTANCE.entity = entity;
        INSTANCE.amount = amount;
        Events.fire(INSTANCE);
        INSTANCE.entity = null;
        INSTANCE.source = null;
    }
}
