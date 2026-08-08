package mindustry.net;

public final class YZFNetworkMetrics{
    private YZFNetworkMetrics(){
    }

    public static volatile long lastSampleAtMillis;
    public static volatile long uploadBytesPerSecond;
    public static volatile long downloadBytesPerSecond;
    public static volatile long uploadBytesAccum;
    public static volatile long downloadBytesAccum;
    public static volatile long syncClientSnapshots;
    public static volatile long syncDroppedSnapshots;
    public static volatile long syncCorrections;
    public static volatile long syncRubberbands;
    public static volatile long syncForcedReliableSnapshots;
    public static volatile float syncLastPositionError;

    // Per-packet size tracking (upload direction), window = time between sampleNow() calls.
    public static volatile long uploadPacketMax;
    public static volatile long uploadPacketMin;
    public static volatile long uploadPacketCount;
    public static volatile long uploadPacketBytes;
    // Values of the last completed window, exposed to the gateway.
    public static volatile long lastUploadPacketMax;
    public static volatile long lastUploadPacketMin;
    public static volatile long lastUploadPacketCount;
    public static volatile long lastUploadPacketBytes;

    public static synchronized void recordUpload(long bytes){
        if(bytes > 0L){
            uploadBytesAccum += bytes;
            uploadPacketCount++;
            uploadPacketBytes += bytes;
            if(bytes > uploadPacketMax) uploadPacketMax = bytes;
            if(uploadPacketMin == 0L || bytes < uploadPacketMin) uploadPacketMin = bytes;
        }
    }

    public static synchronized void recordDownload(long bytes){
        if(bytes > 0L){
            downloadBytesAccum += bytes;
        }
    }

    public static synchronized void recordClientSnapshot(){
        syncClientSnapshots++;
    }

    public static synchronized void recordDroppedSnapshot(){
        syncDroppedSnapshots++;
    }

    public static synchronized void recordSyncCorrection(float error){
        syncCorrections++;
        syncLastPositionError = error;
    }

    public static synchronized void recordSyncRubberband(){
        syncRubberbands++;
    }

    public static synchronized void recordForcedReliableSnapshot(){
        syncForcedReliableSnapshots++;
    }

    public static synchronized void recordPositionError(float error){
        syncLastPositionError = error;
    }

    public static synchronized void sampleNow(){
        long now = System.currentTimeMillis();
        if(lastSampleAtMillis == 0L){
            lastSampleAtMillis = now;
            uploadBytesPerSecond = 0L;
            downloadBytesPerSecond = 0L;
            return;
        }

        long elapsed = Math.max(1L, now - lastSampleAtMillis);
        uploadBytesPerSecond = (uploadBytesAccum * 1000L) / elapsed;
        downloadBytesPerSecond = (downloadBytesAccum * 1000L) / elapsed;
        uploadBytesAccum = 0L;
        downloadBytesAccum = 0L;
        lastUploadPacketMax = uploadPacketMax;
        lastUploadPacketMin = uploadPacketMin;
        lastUploadPacketCount = uploadPacketCount;
        lastUploadPacketBytes = uploadPacketBytes;
        uploadPacketMax = 0L;
        uploadPacketMin = 0L;
        uploadPacketCount = 0L;
        uploadPacketBytes = 0L;
        lastSampleAtMillis = now;
    }

    public static long currentUploadBps(){
        return uploadBytesPerSecond;
    }

    public static long currentDownloadBps(){
        return downloadBytesPerSecond;
    }

    public static long syncClientSnapshots(){
        return syncClientSnapshots;
    }

    public static long syncDroppedSnapshots(){
        return syncDroppedSnapshots;
    }

    public static long syncCorrections(){
        return syncCorrections;
    }

    public static long syncRubberbands(){
        return syncRubberbands;
    }

    public static long syncForcedReliableSnapshots(){
        return syncForcedReliableSnapshots;
    }

    public static float syncLastPositionError(){
        return syncLastPositionError;
    }

    public static long lastUploadPacketMax(){
        return lastUploadPacketMax;
    }

    public static long lastUploadPacketMin(){
        return lastUploadPacketMin;
    }

    public static long lastUploadPacketCount(){
        return lastUploadPacketCount;
    }

    public static long lastUploadPacketAvg(){
        return lastUploadPacketCount > 0L ? lastUploadPacketBytes / lastUploadPacketCount : 0L;
    }
}
