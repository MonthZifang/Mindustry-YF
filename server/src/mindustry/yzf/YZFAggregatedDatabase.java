package mindustry.yzf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Non-blocking facade over several registered databases. Reads race healthy
 * replicas; writes fan out in parallel and report every failed backend.
 */
public final class YZFAggregatedDatabase implements AutoCloseable{
    private final YZFDatabaseRegistry registry;
    private final List<String> databaseIds;
    private final Executor executor;
    private final boolean ownsExecutor;

    public YZFAggregatedDatabase(YZFDatabaseRegistry registry, List<String> databaseIds){
        this(registry, databaseIds, Executors.newFixedThreadPool(Math.max(2, databaseIds == null ? 2 : databaseIds.size())), true);
    }

    public YZFAggregatedDatabase(YZFDatabaseRegistry registry, List<String> databaseIds, Executor executor){
        this(registry, databaseIds, executor, false);
    }

    private YZFAggregatedDatabase(YZFDatabaseRegistry registry, List<String> databaseIds, Executor executor, boolean ownsExecutor){
        if(registry == null) throw new IllegalArgumentException("registry cannot be null");
        this.registry = registry;
        this.databaseIds = List.copyOf(databaseIds == null ? List.of() : databaseIds);
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public CompletableFuture<String> get(String category, String key){
        List<CompletableFuture<String>> reads = new ArrayList<>();
        for(String id : databaseIds){
            reads.add(CompletableFuture.supplyAsync(() -> {
                try{return registry.get(id, category, key);}catch(Exception e){return null;}
            }, executor));
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        if(reads.isEmpty()) result.complete(null);
        for(CompletableFuture<String> read : reads) read.thenAccept(value -> { if(value != null) result.complete(value); });
        CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).thenRun(() -> result.complete(null));
        return result;
    }

    public CompletableFuture<WriteResult> set(String category, String key, String valueJson){
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for(String id : databaseIds){
            writes.add(CompletableFuture.runAsync(() -> { try{registry.set(id, category, key, valueJson);}catch(Exception e){throw new CompletionException(e);} }, executor));
        }
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).handle((ignored, error) -> new WriteResult(writes.size(), countFailures(writes), error));
    }

    private int countFailures(List<CompletableFuture<Void>> futures){
        int failed = 0; for(CompletableFuture<Void> future : futures) if(future.isCompletedExceptionally()) failed++; return failed;
    }

    public record WriteResult(int targets, int failures, Throwable aggregateError){
        public boolean successful(){ return failures == 0; }
    }

    @Override public void close(){
        if(ownsExecutor && executor instanceof java.util.concurrent.ExecutorService service) service.shutdown();
    }
}
