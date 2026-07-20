package mindustry.yzf;

import arc.Core;
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
import java.util.concurrent.atomic.AtomicBoolean;

public final class YZFFileWatcher{
    private final YZFPaths paths;
    private final YZFScriptRuntime runtime;

    private WatchService watchService;
    private Thread thread;
    private volatile boolean running;
    private final AtomicBoolean overflowReloadPending = new AtomicBoolean();

    public YZFFileWatcher(YZFPaths paths, YZFModuleRegistry registry, YZFScriptRuntime runtime){
        this.paths = paths;
        this.runtime = runtime;
    }

    public synchronized boolean start(){
        if(running) return false;

        try{
            watchService = FileSystems.getDefault().newWatchService();
            registerTree(paths.scriptsDir.file().toPath());
            registerTree(paths.modulesDir.file().toPath());
            registerTree(paths.pluginsDir.file().toPath());
            registerTree(paths.compatDir.file().toPath());
            registerTree(paths.configDir.file().toPath());
        }catch(IOException e){
            Log.err("[@] 启动文件监听失败。", MindustryYZF.name, e);
            closeQuietly();
            return false;
        }

        running = true;
        thread = new Thread(this::runLoop, "MindustryYZF-FileWatcher");
        thread.setDaemon(true);
        thread.start();
        Log.info("[@] 文件监听已开启。", MindustryYZF.name);
        return true;
    }

    public synchronized boolean stop(){
        if(!running){
            closeQuietly();
            thread = null;
            return false;
        }
        running = false;
        closeQuietly();
        if(thread != null){
            thread.interrupt();
            thread = null;
        }
        Log.info("[@] 文件监听已关闭。", MindustryYZF.name);
        return true;
    }

    public synchronized boolean restart(){
        stop();
        return start();
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
                    if(event.kind() == StandardWatchEventKinds.OVERFLOW){
                        requestOverflowReload();
                        continue;
                    }
                    Path changed = watchPath.resolve((Path)event.context());
                    if(changed.normalize().toString().replace('\\', '/').endsWith("/config/external-access.hjson")){
                        Core.app.post(MindustryYZF::reloadExternalAccess);
                        continue;
                    }
                    if(Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE){
                        registerTree(changed);
                    }

                    if(isRelevantChange(changed)){
                        runtime.onFileChange(changed);
                    }
                }

                if(!key.reset()){
                    break;
                }
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
                break;
            }catch(ClosedWatchServiceException ignored){
                break;
            }catch(Throwable t){
                Log.err("[@] 文件监听循环异常。", MindustryYZF.name, t);
            }
        }

        running = false;
        closeQuietly();
    }

    private void requestOverflowReload(){
        if(!overflowReloadPending.compareAndSet(false, true)) return;
        Log.warn("[@] File watcher overflow detected; reloading all runtime modules.", MindustryYZF.name);
        Core.app.post(() -> {
            try{
                if(!MindustryYZF.isShuttingDown()) runtime.reloadAll();
            }finally{
                overflowReloadPending.set(false);
            }
        });
    }

    private boolean isRelevantChange(Path changed){
        if(changed == null) return false;
        if(Files.isDirectory(changed)) return false;
        if(isIgnoredPath(changed)) return false;

        String name = fileName(changed).toLowerCase(Locale.ROOT);
        if(name.isEmpty()) return false;
        if(name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak") || name.endsWith(".swp")) return false;

        if(name.equals("module.hjson") || name.equals("module.json")) return true;

        // Runtime plugin configuration is stored in data/config/config.hjson.
        // It must trigger a module reload just like the module metadata and main script.
        if((name.equals("runtime.hjson") || name.equals("config.hjson")) && isRuntimeConfigPath(changed)) return true;

        for(String ext : YZFModuleLoader.supportedScriptExtensions()){
            if(name.endsWith("." + ext.toLowerCase(Locale.ROOT))){
                return true;
            }
        }

        return false;
    }

    private void registerTree(Path root) throws IOException{
        if(root == null || !Files.exists(root) || isIgnoredPath(root)) return;

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

    private boolean isIgnoredPath(Path path){
        if(path == null) return true;

        for(Path part : path.normalize()){
            String segment = part.toString().toLowerCase(Locale.ROOT);
            if(segment.equals("cache") || segment.equals("logs") || segment.equals("tmp") || segment.equals("temp") || segment.equals("node_modules")){
                return true;
            }
        }

        return false;
    }

    private boolean isRuntimeConfigPath(Path path){
        if(path == null) return false;
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/config/runtime.hjson") || normalized.endsWith("/data/config/config.hjson");
    }

    private String fileName(Path path){
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
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
