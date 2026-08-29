package arc.net;

import arc.net.FrameworkMessage.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Server-side ArcNet connection with optional byte-paced TCP output.
 *
 * Packets keep their original ArcNet framing. The serialized byte stream is written in
 * smaller socket batches, so unmodified Mindustry clients reassemble it through TCP.
 * UDP packets are moved to the same TCP stream while shaping is active; stale queued UDP
 * updates of the same packet type are coalesced, while reliable packets are never dropped.
 */
public class ShapedConnection extends Connection{
    private static final int minBytesPerMinute = 512;
    private static final int minChunkBytes = 32;
    private static final int maxChunkBytes = 16 * 1024;
    private static final int maxFrameBytes = 64 * 1024;
    private static final int maxExpandableFrameBytes = 16 * 1024 * 1024;
    // Do not emit oversized UDP datagrams. Unmodified Mindustry clients have
    // no application-level UDP reassembly protocol; large datagrams are sent
    // as length-framed TCP instead, while small latency-sensitive updates stay UDP.
    private static final int maxUdpDatagramBytes = 1200;
    private static final int maxCadenceMillis = 6000;
    private static final long maxQueuedBytes = 16L * 1024L * 1024L;

    private static final ScheduledThreadPoolExecutor pacer = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "ArcNet-Traffic-Pacer");
        thread.setDaemon(true);
        return thread;
    });

    static{
        pacer.setRemoveOnCancelPolicy(true);
    }

    private final Object shapingLock = new Object();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private volatile boolean shapingEnabled;
    private volatile long bytesPerMinute = 1024L * 1024L;
    private volatile int chunkBytes = 1024;
    private volatile int intervalMillis = 20;
    private boolean drainScheduled;
    private boolean drainRunning;
    private boolean closed;
    private long nextWriteNanos;
    private long queuedBytes;

    private final AtomicLong tcpBytesSent = new AtomicLong();
    private final AtomicLong udpBytesSent = new AtomicLong();
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong chunksSent = new AtomicLong();
    private final AtomicLong splitPackets = new AtomicLong();
    private final AtomicLong coalescedPackets = new AtomicLong();
    private final AtomicLong droppedUnreliablePackets = new AtomicLong();
    private volatile long lastWriteMillis;

    public void configureTrafficShaping(boolean enabled, long bytesPerMinute, int chunkBytes, int intervalMillis){
        synchronized(shapingLock){
            this.bytesPerMinute = Math.max(minBytesPerMinute, bytesPerMinute);
            this.chunkBytes = Math.max(minChunkBytes, Math.min(maxChunkBytes, chunkBytes));
            this.intervalMillis = Math.max(1, Math.min(60_000, intervalMillis));
            shapingEnabled = enabled;
            if(!enabled) nextWriteNanos = 0L;
            if(!frames.isEmpty()) scheduleDrainLocked(0L);
        }
    }

    public boolean trafficShapingEnabled(){
        return shapingEnabled;
    }

    public long trafficBytesPerMinute(){
        return bytesPerMinute;
    }

    public int trafficChunkBytes(){
        return chunkBytes;
    }

    public int trafficEffectiveChunkBytes(){
        return effectiveChunkBytes();
    }

    public int trafficIntervalMillis(){
        return intervalMillis;
    }

    public long trafficQueuedBytes(){
        synchronized(shapingLock){
            return queuedBytes;
        }
    }

    public int trafficQueuedPackets(){
        synchronized(shapingLock){
            return frames.size();
        }
    }

    public long trafficTcpBytesSent(){
        return tcpBytesSent.get();
    }

    public long trafficUdpBytesSent(){
        return udpBytesSent.get();
    }

    public long trafficPacketsSent(){
        return packetsSent.get();
    }

    public long trafficChunksSent(){
        return chunksSent.get();
    }

    public long trafficSplitPackets(){
        return splitPackets.get();
    }

    public long trafficCoalescedPackets(){
        return coalescedPackets.get();
    }

    public long trafficDroppedUnreliablePackets(){
        return droppedUnreliablePackets.get();
    }

    public long trafficLastWriteMillis(){
        return lastWriteMillis;
    }

    @Override
    public int sendTCP(Object object){
        if(object == null) throw new IllegalArgumentException("object cannot be null.");
        synchronized(shapingLock){
            if(shouldQueueLocked()){
                byte[] frame = serializeTcp(object);
                enqueueLocked(new Frame(frame, true, null));
                return frame.length;
            }
            int sent = super.sendTCP(object);
            recordImmediate(sent, true);
            return sent;
        }
    }

    @Override
    public int sendTCPBuffer(ByteBuffer buffer){
        if(buffer == null) throw new IllegalArgumentException("buffer cannot be null.");
        synchronized(shapingLock){
            if(shouldQueueLocked()){
                byte[] frame = copyBuffer(buffer);
                enqueueLocked(new Frame(frame, true, null));
                return frame.length;
            }
            int sent = super.sendTCPBuffer(buffer);
            recordImmediate(sent, true);
            return sent;
        }
    }

    @Override
    public int sendUDP(Object object){
        if(object == null) throw new IllegalArgumentException("object cannot be null.");
        synchronized(shapingLock){
            byte[] serialized = serializeTcp(object);
            if(shouldQueueLocked() || serialized.length > maxUdpDatagramBytes){
                enqueueLocked(new Frame(serialized, false, packetKey(object)));
                return serialized.length;
            }
            int sent = super.sendUDP(object);
            recordImmediate(sent, false);
            return sent;
        }
    }

    @Override
    public int sendUDPBuffer(ByteBuffer buffer){
        if(buffer == null) throw new IllegalArgumentException("buffer cannot be null.");
        synchronized(shapingLock){
            if(shouldQueueLocked() || copyBuffer(buffer).length + tcp.serialization.getLengthLength() > maxUdpDatagramBytes){
                byte[] packet = copyBuffer(buffer);
                byte[] frame = framePacket(packet);
                // A pre-serialized broadcast has no object metadata here. Multiple
                // entity snapshot frames can share the same packet ID, so treating
                // the first byte as a coalescing key would discard snapshot segments.
                enqueueLocked(new Frame(frame, false, null));
                return frame.length;
            }
            int sent = super.sendUDPBuffer(buffer);
            recordImmediate(sent, false);
            return sent;
        }
    }

    @Override
    public void close(DcReason reason){
        synchronized(shapingLock){
            closed = true;
            frames.clear();
            queuedBytes = 0L;
        }
        super.close(reason);
    }

    private boolean shouldQueueLocked(){
        return shapingEnabled || drainRunning || drainScheduled || !frames.isEmpty();
    }

    private void enqueueLocked(Frame frame){
        if(frame.data.length == 0) return;
        if(!frame.reliable && frame.coalesceKey != null){
            Iterator<Frame> iterator = frames.descendingIterator();
            while(iterator.hasNext()){
                Frame queued = iterator.next();
                if(!queued.reliable && queued.offset == 0 && frame.coalesceKey.equals(queued.coalesceKey)){
                    queuedBytes -= queued.remaining();
                    iterator.remove();
                    coalescedPackets.incrementAndGet();
                    break;
                }
            }
        }
        if(queuedBytes + frame.data.length > maxQueuedBytes && !frame.reliable){
            droppedUnreliablePackets.incrementAndGet();
            return;
        }
        frames.addLast(frame);
        queuedBytes += frame.data.length;
        if(frame.data.length > effectiveChunkBytes()) splitPackets.incrementAndGet();
        scheduleDrainLocked(0L);
    }

    private void scheduleDrainLocked(long delayNanos){
        if(closed || drainScheduled) return;
        drainScheduled = true;
        pacer.schedule(this::drainOne, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
    }

    private void drainOne(){
        synchronized(shapingLock){
            drainScheduled = false;
            if(closed || frames.isEmpty()) return;

            long now = System.nanoTime();
            if(shapingEnabled && nextWriteNanos > now){
                scheduleDrainLocked(nextWriteNanos - now);
                return;
            }

            drainRunning = true;
            Frame frame = frames.peekFirst();
            int amount = shapingEnabled ? Math.min(frame.remaining(), effectiveChunkBytes()) : frame.remaining();
            ByteBuffer part = ByteBuffer.wrap(frame.data, frame.offset, amount).slice();
            int sent = super.sendTCPBuffer(part);
            if(sent <= 0){
                drainRunning = false;
                if(!isConnected()){
                    frames.clear();
                    queuedBytes = 0L;
                }else{
                    scheduleDrainLocked(TimeUnit.MILLISECONDS.toNanos(1));
                }
                return;
            }

            // Socket writes are allowed to be partial. Advance only by the
            // number of bytes actually accepted, otherwise the remainder of
            // a framed packet would be silently skipped and corrupt the stream.
            int written = Math.min(sent, amount);
            frame.offset += written;
            queuedBytes -= written;
            tcpBytesSent.addAndGet(written);
            chunksSent.incrementAndGet();
            lastWriteMillis = System.currentTimeMillis();
            if(frame.remaining() == 0){
                frames.removeFirst();
                packetsSent.incrementAndGet();
            }

            if(shapingEnabled){
                long byteDelay = (long)Math.ceil(written * 60_000_000_000d / Math.max(minBytesPerMinute, bytesPerMinute));
                long intervalDelay = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
                nextWriteNanos = now + Math.max(byteDelay, intervalDelay);
            }else{
                nextWriteNanos = 0L;
            }
            drainRunning = false;
            if(!frames.isEmpty()) scheduleDrainLocked(shapingEnabled ? Math.max(0L, nextWriteNanos - System.nanoTime()) : 0L);
        }
    }

    private int effectiveChunkBytes(){
        long cadenceBytes = Math.max(minChunkBytes, bytesPerMinute * maxCadenceMillis / 60_000L);
        return (int)Math.max(minChunkBytes, Math.min(chunkBytes, cadenceBytes));
    }

    private void recordImmediate(int bytes, boolean tcp){
        if(bytes <= 0) return;
        if(tcp) tcpBytesSent.addAndGet(bytes);
        else udpBytesSent.addAndGet(bytes);
        packetsSent.incrementAndGet();
        chunksSent.incrementAndGet();
        lastWriteMillis = System.currentTimeMillis();
    }

    private byte[] serializeTcp(Object object){
        int capacity = maxFrameBytes;
        while(true){
            try{
                ByteBuffer buffer = ByteBuffer.allocate(capacity);
                int lengthLength = tcp.serialization.getLengthLength();
                buffer.position(lengthLength);
                tcp.serialization.write(buffer, object);
                int end = buffer.position();
                buffer.position(0);
                tcp.serialization.writeLength(buffer, end - lengthLength);
                buffer.position(end);
                buffer.flip();
                return copyBuffer(buffer);
            }catch(BufferOverflowException overflow){
                if(capacity >= maxExpandableFrameBytes) throw overflow;
                capacity = Math.min(maxExpandableFrameBytes, capacity * 2);
            }
        }
    }

    private byte[] framePacket(byte[] packet){
        ByteBuffer buffer = ByteBuffer.allocate(packet.length + tcp.serialization.getLengthLength());
        tcp.serialization.writeLength(buffer, packet.length);
        buffer.put(packet);
        return buffer.array();
    }

    private static byte[] copyBuffer(ByteBuffer source){
        ByteBuffer copy = source.duplicate();
        copy.rewind();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static String packetKey(Object object){
        if(object instanceof FrameworkMessage) return null;
        // This packet is a complete replacement snapshot. Entity/block snapshots may
        // span multiple packets, so coalescing those by class would discard entities.
        return object.getClass().getSimpleName().equals("StateSnapshotCallPacket")
            ? object.getClass().getName()
            : null;
    }

    private static final class Frame{
        final byte[] data;
        final boolean reliable;
        final String coalesceKey;
        int offset;

        Frame(byte[] data, boolean reliable, String coalesceKey){
            this.data = data;
            this.reliable = reliable;
            this.coalesceKey = coalesceKey;
        }

        int remaining(){
            return data.length - offset;
        }
    }
}
