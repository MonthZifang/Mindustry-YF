package mindustry.yzf;

import mindustry.Vars;
import mindustry.server.ServerLauncher;

/** Stable server metrics API for YZF plugins. */
public final class YZFServerMetrics{
    private YZFServerMetrics(){
    }

    /** Returns the measured headless server update rate from the latest one-second window. */
    public static int actualTps(){
        return ServerLauncher.actualTps();
    }

    /** Returns the configured main-loop TPS cap. */
    public static int tpsLimit(){
        return Vars.serverTps;
    }
}
