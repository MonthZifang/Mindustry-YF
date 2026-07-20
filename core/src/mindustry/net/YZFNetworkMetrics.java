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

    public static synchronized void recordUpload(long bytes){
        if(bytes > 0L){
            uploadBytesAccum += bytes;
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
}
