package mindustry.yzf;

import arc.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class YZFTaskBinding{
    public final String id;
    public final String kind;
    public final Timer.Task task;
    public final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public YZFTaskBinding(String id, String kind, Timer.Task task){
        this.id = id;
        this.kind = kind;
        this.task = task;
    }

    public boolean cancel(){
        if(!cancelled.compareAndSet(false, true)) return false;
        task.cancel();
        return true;
    }

    public boolean recordFailure(int threshold){
        return failures.incrementAndGet() >= threshold;
    }

    public void recordSuccess(){
        failures.set(0);
    }
}
