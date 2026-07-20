package mindustry.yzf;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class YZFMemoryRegion{
    public enum Mode{ LOGICAL, CLASSLOADER, PROCESS }
    public enum State{ CREATED, STARTING, ACTIVE, DRAINING, STOPPED, FAILED }

    private final String id;
    private final Mode mode;
    private final long createdAt = System.currentTimeMillis();
    private final long minHeap;
    private final long maxHeap;
    private volatile State state = State.CREATED;
    private volatile String lastError = "";
    private volatile URLClassLoader classLoader;
    private volatile Process process;
    private volatile BufferedWriter processWriter;

    YZFMemoryRegion(String id, Mode mode, long minHeap, long maxHeap){
        this.id = id;
        this.mode = mode;
        this.minHeap = minHeap;
        this.maxHeap = maxHeap;
    }

    public String id(){ return id; }
    public Mode mode(){ return mode; }
    public State state(){ return state; }
    public long minHeap(){ return minHeap; }
    public long maxHeap(){ return maxHeap; }
    public String lastError(){ return lastError; }
    public long createdAt(){ return createdAt; }

    void start(URLClassLoader loader){
        classLoader = loader;
        state = State.ACTIVE;
    }

    synchronized void replaceClassLoader(URLClassLoader loader){
        if(classLoader != null){
            try{ classLoader.close(); }catch(Exception ignored){}
        }
        classLoader = loader;
        state = State.ACTIVE;
    }

    void start(Process process){
        this.process = process;
        this.processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        state = State.ACTIVE;
    }

    void starting(){ state = State.STARTING; }
    void fail(Throwable error){
        lastError = error == null ? "unknown" : String.valueOf(error.getMessage());
        state = State.FAILED;
    }

    public synchronized void stop(){
        if(state == State.STOPPED) return;
        state = State.DRAINING;
        try{
            if(processWriter != null){
                processWriter.write("shutdown\n");
                processWriter.flush();
                processWriter.close();
            }
        }catch(Exception ignored){
        }
        if(process != null && process.isAlive()){
            process.destroy();
            try{
                if(!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if(classLoader != null){
            try{ classLoader.close(); }catch(Exception ignored){}
        }
        process = null;
        processWriter = null;
        classLoader = null;
        state = State.STOPPED;
    }

    public Map<String, Object> snapshot(){
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("mode", mode.name().toLowerCase());
        result.put("state", state.name().toLowerCase());
        result.put("createdAt", createdAt);
        result.put("minHeap", minHeap);
        result.put("maxHeap", maxHeap);
        result.put("lastError", lastError);
        if(process != null) result.put("pid", process.pid());
        return result;
    }
}
