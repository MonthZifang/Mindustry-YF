package mindustry.events;

import arc.*;
import arc.util.*;
import mindustry.net.*;

/**
 * Fired on the server when a packet is received from a client, before it is dispatched
 * to the registered handler. External network modules can observe or cancel it by
 * setting {@link #isCancelled}. Mirrors {@link SendPacketEvent} for the inbound path.
 */
public class ReceivePacketEvent{
    /** The connection the packet came from. */
    public NetConnection con;
    /** The received packet object. */
    public Packet packet;
    /** Set to true to drop the packet before it is handled. */
    public boolean isCancelled;

    private ReceivePacketEvent(){
    }

    private static final ReceivePacketEvent inst = new ReceivePacketEvent();

    /** @return isCancelled */
    public static boolean emit(NetConnection con, Packet packet){
        inst.isCancelled = false;
        inst.con = con;
        inst.packet = packet;
        Events.fire(inst);
        return inst.isCancelled;
    }
}
