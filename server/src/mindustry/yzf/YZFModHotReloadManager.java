package mindustry.yzf;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.mod.Scripts;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class YZFModHotReloadManager{
    private final YZFContext context;
    private WatchService watchService;
    private Thread thread;
    private volatile boolean running;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private Timer.Task pendingReloadTask;

    public YZFModHotReloadManager(YZFContext context){
        this.context = context;
    }

    public synchronized boolean start(){
        if(running) return false;
        Fi dir = Vars.modDirectory;
        if(dir == null){
            Log.warn("[@] Mindustry mod hot reload skipped: mod directory is unavailable.", MindustryYZF.name);
            return false;
        }
        dir.mkdirs();

        try{
            watchService = FileSystems.getDefault().newWatchService();
            registerTree(dir.file().toPath());
        }catch(IOException e){
            Log.err("[@] Failed to start Mindustry mod hot reload watcher.", MindustryYZF.name, e);
            closeQuietly();
            return false;
        }

        running = true;
        thread = new Thread(this::runLoop, "MindustryYZF-ModHotReload");
        thread.setDaemon(true);
        thread.start();
        Log.info("[@] Mindustry mod hot reload watcher started: @", MindustryYZF.name, dir.absolutePath());
        return true;
    }

    public synchronized boolean stop(){
        if(!running){
            closeQuietly();
            thread = null;
            cancelPendingReload();
            return false;
        }
        running = false;
        closeQuietly();
        if(thread != null){
            thread.interrupt();
            thread = null;
        }
        cancelPendingReload();
        return true;
    }

    public boolean running(){
        return running;
    }

    public void requestReload(){
        if(!running || MindustryYZF.isShuttingDown()) return;
        if(!scheduled.compareAndSet(false, true)) return;
        pendingReloadTask = Timer.schedule(() -> {
            scheduled.set(false);
            pendingReloadTask = null;
            if(!running || MindustryYZF.isShuttingDown()) return;
            reloadNow();
        }, 0.75f);
    }

    public synchronized void reloadNow(){
        context.runtime.reloadAll();
        if(reloadMindustryScripts()){
            Log.info("[@] Hot reloaded YZF modules and Mindustry script mods.", MindustryYZF.name);
        }else{
            Log.warn("[@] Hot reloaded YZF modules. Java/content Mindustry mods still require a process restart.", MindustryYZF.name);
        }
    }

    private boolean reloadMindustryScripts(){
        try{
            Field scriptsField = Vars.mods.getClass().getDeclaredField("scripts");
            scriptsField.setAccessible(true);
            Object current = scriptsField.get(Vars.mods);
            if(current instanceof Scripts scripts){
                try{
                    scripts.dispose();
                }catch(Throwable error){
                    YZFErrorLog.high("mod-hot-reload", "Failed to dispose Mindustry script runtime", error);
                }
            }
            scriptsField.set(Vars.mods, null);
            Vars.mods.loadScripts();
            return true;
        }catch(Throwable t){
            Log.err("[@] Failed to hot reload Mindustry script mods.", MindustryYZF.name, t);
            return false;
        }
    }

    private void runLoop(){
        while(running){
            try{
                java.nio.file.WatchKey key = watchService.take();
                Path watchPath = (Path)key.watchable();

                for(WatchEvent<?> event : key.pollEvents()){
                    Path changed = watchPath.resolve((Path)event.context());
                    if(Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE){
                        registerTree(changed);
                    }
                    if(isRelevantChange(changed)){
                        requestReload();
                    }
                }

                if(!key.reset()) break;
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
                break;
            }catch(ClosedWatchServiceException ignored){
                break;
            }catch(Throwable t){
                YZFErrorLog.high("mod-hot-reload", "Mindustry mod hot reload watcher failed", t);
                try{ Thread.sleep(100L); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); break; }
            }
        }
        running = false;
        closeQuietly();
    }

    private void registerTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        if(Files.isDirectory(root)){
            root.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            );

            try(var stream = Files.list(root)){
                for(Path child : (Iterable<Path>)stream::iterator){
                    if(Files.isDirectory(child)){
                        registerTree(child);
                    }
                }
            }
        }
    }

    private boolean isRelevantChange(Path changed){
        if(changed == null || Files.isDirectory(changed)) return false;
        String name = changed.getFileName() == null ? "" : changed.getFileName().toString().toLowerCase(Locale.ROOT);
        if(name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak") || name.endsWith(".swp")) return false;
        return name.endsWith(".js") || name.endsWith(".zip") || name.endsWith(".jar") || name.equals("mod.json") || name.equals("mod.hjson") || name.equals("plugin.json") || name.equals("plugin.hjson");
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

    private void cancelPendingReload(){
        scheduled.set(false);
        if(pendingReloadTask != null){
            pendingReloadTask.cancel();
            pendingReloadTask = null;
        }
    }
}
