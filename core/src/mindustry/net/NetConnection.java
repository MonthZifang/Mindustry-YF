package mindustry.net;

import arc.struct.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.net.Packets.*;

import java.io.*;

import static mindustry.Vars.*;

public abstract class NetConnection{
    public final String address;
    public String uuid = "AAAAAAAA", usid = uuid;
    public boolean mobile, modclient;
    public @Nullable Player player;
    public boolean kicked = false;

    /** When this connection was established. */
    public long connectTime = Time.millis();
    /** ID of last received client snapshot. */
    public int lastReceivedClientSnapshot = -1;
    /** Timestamp of last received snapshot. */
    public long lastReceivedClientTime;
    /** YZF sync metrics for this connection. */
    public long syncClientSnapshots, syncDroppedSnapshots, syncCorrectionCount, syncRubberbandCount;
    public float syncLastPositionError;
    /** Build requests that have been recently rejected. This is cleared every snapshot. */
    public Seq<BuildPlan> rejectedRequests = new Seq<>();
    /** Handles chat spam rate limits. */
    public Ratekeeper chatRate = new Ratekeeper();
    /** Handles packet spam rate limits. */
    public Ratekeeper packetRate = new Ratekeeper();
    /** Entities that only this player will get synced to them. */
    public Seq<Syncc> localEntities = new Seq<>(false);

    // ---- YZF per-connection traffic counters (updated by gateway + serializer) ----
    /** Total bytes uploaded (server → this client). */
    public volatile long trafficUploadBytes;
    /** Total bytes downloaded (this client → server). */
    public volatile long trafficDownloadBytes;
    /** Total packets sent to this client. */
    public volatile long trafficPacketsSent;
    /** Total packets received from this client. */
    public volatile long trafficPacketsReceived;
    /** Last activity timestamp (millis). */
    public volatile long trafficLastActivity;

    /** Applies or removes the server-to-client byte pacer for this connection. */
    public void configureTrafficShaping(boolean enabled, long bytesPerMinute, int chunkBytes, int intervalMillis){
    }

    public boolean trafficShapingEnabled(){ return false; }
    public long trafficBytesPerMinute(){ return 0L; }
    public int trafficChunkBytes(){ return 0; }
    public int trafficEffectiveChunkBytes(){ return 0; }
    public int trafficIntervalMillis(){ return 0; }
    public long trafficQueuedBytes(){ return 0L; }
    public int trafficQueuedPackets(){ return 0; }
    public long trafficBytesSent(){ return trafficUploadBytes; }
    public long trafficBytesReceived(){ return trafficDownloadBytes; }
    public long trafficSentPackets(){ return trafficPacketsSent; }
    public long trafficReceivedPackets(){ return trafficPacketsReceived; }
    public long trafficLastActivityMillis(){ return trafficLastActivity; }
    public long trafficChunksSent(){ return 0L; }
    public long trafficSplitPackets(){ return 0L; }
    public long trafficCoalescedPackets(){ return 0L; }
    public long trafficDroppedUnreliablePackets(){ return 0L; }

    //TODO: refactor to state enum
    public boolean hasConnected, hasBegunConnecting, determiningAssets, receivingAssets, hasDisconnected;
    public float viewWidth, viewHeight, viewX, viewY;

    public NetConnection(String address){
        this.address = address;
    }

    /** Kick with the standard kick reason. */
    public void kick(){
        kick(KickReason.kick);
    }

    /** Kick with a special, localized reason. Use this if possible. */
    public void kick(KickReason reason){
        kick(reason, (reason == KickReason.kick || reason == KickReason.banned || reason == KickReason.vote) ? 30 * 1000 : 0);
    }

    /** Kick with a special, localized reason. Use this if possible. */
    public void kick(KickReason reason, long kickDuration){
        kick(null, reason, kickDuration);
    }

    /** Kick with an arbitrary reason. */
    public void kick(String reason){
        kick(reason, null, 30 * 1000);
    }

    /** Kick with an arbitrary reason. */
    public void kick(String reason, long duration){
        kick(reason, null, duration);
    }

    /** Kick with an arbitrary reason, and a kick duration in milliseconds. */
    private void kick(String reason, @Nullable KickReason kickType, long kickDuration){
        if(kicked) return;

        Log.info("Kicking connection @ / @; Reason: @", address, uuid, reason == null ? kickType.name() : reason.replace("\n", " "));

        if(kickDuration > 0){
            netServer.admins.handleKicked(uuid, address, kickDuration);
        }

        if(reason == null){
            Call.kick(this, kickType);
        }else{
            Call.kick(this, reason);
        }

        kickDisconnect();

        netServer.admins.save();
        kicked = true;
    }

    protected void kickDisconnect(){
        close();
    }

    public boolean isConnected(){
        return true;
    }

    public void sendStreamAsync(Streamable stream, ByteArrayOutputStream data){
        //default implementation assumes async stream sending is allowed
        sendStream(stream, data);
    }

    public void sendStream(Streamable stream, ByteArrayOutputStream data){
        stream.stream = new ByteArrayInputStream(data.toByteArray());
        sendStream(stream);
    }

    public void sendStream(Streamable stream){
        try{
            int cid;
            StreamBegin begin = new StreamBegin();
            begin.total = stream.stream.available();
            begin.type = Net.getPacketId(stream);
            send(begin, true);
            cid = begin.id;

            while(stream.stream.available() > 0){
                byte[] bytes = new byte[Math.min(maxTcpSize, stream.stream.available())];
                stream.stream.read(bytes);

                StreamChunk chunk = new StreamChunk();
                chunk.id = cid;
                chunk.data = bytes;
                send(chunk, true);
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public void blacklist(){
        netServer.admins.blacklistDos(address);
    }

    public abstract void send(Object object, boolean reliable);

    public abstract void close();
}
