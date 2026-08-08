package mindustry.yzf;

import arc.files.Fi;
import arc.util.Log;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the core network module directory (config/yzf/netmods/) and hot-reloads
 * modules automatically when files change - no server restart and no manual command.
 *
 * Triggers (debounced by 1 second):
 * - module binary replaced/recompiled (.exe / .bin / .elf / extensionless executable)
 * - netmodule.hjson / netmodule.json metadata edited (command, args, enabled, priority)
 * - config.hjson / config.json edited (modules read their config at startup)
 * - a module folder added or removed
 *
 * Applies changes through YZFNetGateway.onNetModFilesChanged() on a worker thread:
 * new modules start, removed/disabled modules stop, changed binaries restart.
 *
 * Note for Windows: a running module's .exe is locked by the OS. Build scripts should
 * compile to a temporary file, stop the module via POST /yzfnet/netmods/stop (which
 * releases the lock), then replace the binary; this watcher restarts it automatically.
 */
public final class YZFNetModHotReloadWatcher{
    private static final float DEBOUNCE_SECONDS = 1.0f;

    private final YZFNetGateway gateway;
    private final Fi netmodsDir;
    private WatchService watchService;
    private Thread thread;
    private volatile boolean running;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingReload;

    public YZFNetModHotReloadWatcher(YZFNetGateway gateway, Fi netmodsDir){
        this.gateway = gateway;
        this.netmodsDir = netmodsDir;
    }

    public synchronized boolean start(){
        if(running) return false;
        netmodsDir.mkdirs();
        try{
            watchService = FileSystems.getDefault().newWatchService();
            registerTree(netmodsDir.file().toPath());
        }catch(IOException error){
            Log.err("[NetGateway] 核心网络模块热重载监听启动失败。", error);
            closeQuietly();
            return false;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread worker = new Thread(runnable, "YZFNetGateway-NetModHotReload");
            worker.setDaemon(true);
            return worker;
        });
        running = true;
        thread = new Thread(this::runLoop, "YZFNetGateway-NetModWatch");
        thread.setDaemon(true);
        thread.start();
        Log.info("[NetGateway] 核心网络模块热重载监听已启动: @（修改二进制/配置后自动生效）", netmodsDir.absolutePath());
        return true;
    }

    public synchronized boolean stop(){
        if(!running){
            closeQuietly();
            return false;
        }
        running = false;
        closeQuietly();
        if(thread != null){
            thread.interrupt();
            thread = null;
        }
        if(scheduler != null){
            scheduler.shutdownNow();
            scheduler = null;
        }
        pendingReload = null;
        return true;
    }

    public boolean running(){
        return running;
    }

    private void runLoop(){
        while(running){
            try{
                java.nio.file.WatchKey key = watchService.take();
                Path watchPath = (Path)key.watchable();

                for(WatchEvent<?> event : key.pollEvents()){
                    Path changed = watchPath.resolve((Path)event.context());
                    if(Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE){
                        try{
                            registerTree(changed);
                        }catch(IOException ignored){
                        }
                        requestReload();
                    }else if(event.kind() == StandardWatchEventKinds.ENTRY_DELETE && Files.isDirectory(watchPath)){
                        // A module folder may have been removed.
                        requestReload();
                    }else if(isRelevantChange(changed)){
                        requestReload();
                    }
                }

                if(!key.reset()) break;
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
                break;
            }catch(ClosedWatchServiceException ignored){
                break;
            }catch(Throwable error){
                YZFErrorLog.high("netgateway", "Net module hot reload watcher failed", error);
                try{
                    Thread.sleep(200L);
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        running = false;
        closeQuietly();
    }

    /** Debounced: collapses bursts of file events into a single apply pass. */
    private void requestReload(){
        if(!running || MindustryYZF.isShuttingDown()) return;
        if(!scheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService executor = scheduler;
        if(executor == null || executor.isShutdown()) return;
        try{
            pendingReload = executor.schedule(() -> {
                scheduled.set(false);
                pendingReload = null;
                if(!running || MindustryYZF.isShuttingDown()) return;
                try{
                    gateway.onNetModFilesChanged();
                }catch(Throwable error){
                    YZFErrorLog.high("netgateway", "Failed to apply net module changes", error);
                }
            }, (long)(DEBOUNCE_SECONDS * 1000), TimeUnit.MILLISECONDS);
        }catch(java.util.concurrent.RejectedExecutionException ignored){
            scheduled.set(false);
        }
    }

    private void registerTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        if(Files.isDirectory(root)){
            root.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
            try(var stream = Files.list(root)){
                for(Path child : (Iterable<Path>)stream::iterator){
                    if(Files.isDirectory(child)){
                        registerTree(child);
                    }
                }
            }
        }
    }

    /**
     * A change is relevant if it touches a module binary, metadata/config, or any
     * build-relevant source file (.c/.cpp/.h/.go/.rs/.bat/build scripts...). Binary
     * replacements trigger a plain restart; source changes trigger a hot compile.
     * Known build/debug artifacts are ignored.
     */
    private boolean isRelevantChange(Path changed){
        if(changed == null || Files.isDirectory(changed)) return false;
        String name = changed.getFileName() == null ? "" : changed.getFileName().toString().toLowerCase(Locale.ROOT);
        // Metadata / config
        if(name.equals("netmodule.hjson") || name.equals("netmodule.json")) return true;
        if(name.equals("config.hjson") || name.equals("config.json")) return true;
        // Module binaries (external replacement triggers restart)
        if(name.endsWith(".exe") || name.endsWith(".bin") || name.endsWith(".elf")) return true;
        // Ignore known build/debug artifacts and misc files
        if(name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak") || name.endsWith(".swp")) return false;
        if(name.endsWith(".obj") || name.endsWith(".o") || name.endsWith(".pdb") || name.endsWith(".ilk")) return false;
        if(name.endsWith(".exp") || name.endsWith(".lib") || name.endsWith(".map") || name.endsWith(".d")) return false;
        if(name.endsWith(".class") || name.endsWith(".lock")) return false;
        // Source files and build scripts -> hot compile (delegates to the gateway).
        if(YZFNetGateway.isBuildRelevantFile(name)) return true;
        // Ignore other hjson/json (runtime data) and docs.
        if(name.endsWith(".hjson") || name.endsWith(".json") || name.endsWith(".md") || name.endsWith(".txt")) return false;
        // Extensionless executable (typical on Linux)
        return name.indexOf('.') < 0;
    }

    private void closeQuietly(){
        if(watchService != null){
            try{
                watchService.close();
            }catch(IOException ignored){
            }
            watchService = null;
        }
    }
}
