package mindustry.yzf;

import java.util.concurrent.atomic.AtomicLong;

public final class YZFMetrics{
    public final long startedAtMillis = System.currentTimeMillis();
    public final AtomicLong moduleReloads = new AtomicLong();
    public final AtomicLong moduleLoads = new AtomicLong();
    public final AtomicLong moduleFailures = new AtomicLong();
    public final AtomicLong moduleRollbacks = new AtomicLong();
    public final AtomicLong serviceLoads = new AtomicLong();
    public final AtomicLong serviceFailures = new AtomicLong();
    public final AtomicLong serverCommandCalls = new AtomicLong();
    public final AtomicLong playerCommandCalls = new AtomicLong();
    public final AtomicLong remoteCalls = new AtomicLong();
    public final AtomicLong serviceCalls = new AtomicLong();
    public final AtomicLong sqlCalls = new AtomicLong();
    public final AtomicLong redisCalls = new AtomicLong();
    public final AtomicLong permissionDenied = new AtomicLong();
    public final AtomicLong protocolIn = new AtomicLong();
    public final AtomicLong protocolOut = new AtomicLong();
    public final AtomicLong auditEvents = new AtomicLong();
    public final AtomicLong callbackFailures = new AtomicLong();

    public volatile long lastReloadAtMillis;
    public volatile long lastFailureAtMillis;
    public volatile String lastFailure = "";

    public void markReload(){
        lastReloadAtMillis = System.currentTimeMillis();
    }

    public void markFailure(String detail){
        lastFailureAtMillis = System.currentTimeMillis();
        lastFailure = detail == null ? "" : detail;
    }
}
