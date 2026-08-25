package mindustry.events;

import arc.*;
import arc.util.*;
import mindustry.net.*;

/** Fired before a stream (map data, assets, etc.) is sent to a connection. */
public class SendStreamEvent{
    @Nullable
    public NetConnection con;
    public Object stream;
    public long size;
    public boolean isCancelled;

    private SendStreamEvent(){
    }

    private static final SendStreamEvent inst = new SendStreamEvent();

    /** @return isCancelled */
    public static boolean emit(@Nullable NetConnection con, Object stream, long size){
        inst.isCancelled = false;
        inst.con = con;
        inst.stream = stream;
        inst.size = size;
        Events.fire(inst);
        return inst.isCancelled;
    }
}