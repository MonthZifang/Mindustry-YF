package mindustry.net;

import arc.files.*;
import arc.util.serialization.*;
import mindustry.gen.*;
import mindustry.net.Administration.*;

import static mindustry.Vars.*;

public final class YZFNetSync{
    private static volatile long reliableSnapshotsUntilMillis;
    private static long lastConfigCheckMillis, lastConfigModified = Long.MIN_VALUE;
    private static boolean externalLoaded, externalNoPlayerHitBox, externalNoUpdatePlayerMovement;
    private static int externalCorrectionReliableMs, externalRubberbandDistance;
    private static String externalSyncReliability = "adaptive";

    private YZFNetSync(){
    }

    public static boolean noPlayerHitBox(){
        reloadExternalConfig();
        return externalLoaded ? externalNoPlayerHitBox : Config.yzfNoPlayerHitBox.bool();
    }

    public static boolean noUpdatePlayerMovement(){
        reloadExternalConfig();
        return externalLoaded ? externalNoUpdatePlayerMovement : Config.yzfNoUpdatePlayerMovement.bool();
    }

    public static float rubberbandDistance(){
        reloadExternalConfig();
        return Math.max(0f, externalLoaded ? externalRubberbandDistance : Config.yzfRubberbandDistance.num());
    }

    public static void markClientCorrection(float error){
        YZFNetworkMetrics.recordSyncCorrection(error);
        if(error >= rubberbandDistance()){
            YZFNetworkMetrics.recordSyncRubberband();
        }

        int reliableMs = Math.max(0, correctionReliableMs());
        if(reliableMs > 0 && "adaptive".equals(syncReliability())){
            reliableSnapshotsUntilMillis = Math.max(reliableSnapshotsUntilMillis, System.currentTimeMillis() + reliableMs);
        }
    }

    public static boolean shouldSendReliable(Object object, boolean reliable){
        if(reliable || !(object instanceof ClientSnapshotCallPacket)){
            return reliable;
        }

        String mode = syncReliability();
        boolean force = "always".equals(mode) || ("adaptive".equals(mode) && System.currentTimeMillis() < reliableSnapshotsUntilMillis);
        if(force){
            YZFNetworkMetrics.recordForcedReliableSnapshot();
        }
        return force;
    }

    private static String syncReliability(){
        reloadExternalConfig();
        String value = externalLoaded ? externalSyncReliability : Config.yzfSyncReliability.string();
        if(value == null) return "adaptive";

        value = value.trim().toLowerCase();
        if(value.equals("always") || value.equals("off") || value.equals("adaptive")){
            return value;
        }
        return "adaptive";
    }

    private static int correctionReliableMs(){
        reloadExternalConfig();
        return externalLoaded ? externalCorrectionReliableMs : Config.yzfCorrectionReliableMs.num();
    }

    private static void reloadExternalConfig(){
        long now = System.currentTimeMillis();
        if(now - lastConfigCheckMillis < 1000L) return;
        lastConfigCheckMillis = now;

        Fi file = externalConfigFile();
        long modified = file.exists() ? file.lastModified() : -1L;
        if(modified == lastConfigModified) return;
        lastConfigModified = modified;

        if(modified < 0L){
            externalLoaded = false;
            return;
        }

        try{
            Jval root = Jval.read(file.readString("UTF-8"));
            if(root == null || !root.isObject()){
                externalLoaded = false;
                return;
            }

            externalSyncReliability = normalizeMode(root.getString("syncReliability", Config.yzfSyncReliability.string()));
            externalNoPlayerHitBox = root.getBool("noPlayerHitBox", Config.yzfNoPlayerHitBox.bool());
            externalCorrectionReliableMs = root.getInt("correctionReliableMs", Config.yzfCorrectionReliableMs.num());
            externalRubberbandDistance = root.getInt("rubberbandDistance", Config.yzfRubberbandDistance.num());
            externalNoUpdatePlayerMovement = root.getBool("noUpdatePlayerMovement", Config.yzfNoUpdatePlayerMovement.bool());
            externalLoaded = true;
        }catch(Throwable ignored){
            externalLoaded = false;
        }
    }

    private static Fi externalConfigFile(){
        return dataDirectory.child("yzf").child("config").child("sync.hjson");
    }

    private static String normalizeMode(String value){
        if(value == null) return "adaptive";

        value = value.trim().toLowerCase();
        if(value.equals("always") || value.equals("off") || value.equals("adaptive")){
            return value;
        }
        return "adaptive";
    }
}
