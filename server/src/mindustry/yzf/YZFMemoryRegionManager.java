package mindustry.yzf;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class YZFMemoryRegionManager{
    private final YZFPaths paths;
    private final ConcurrentHashMap<String, YZFMemoryRegion> regions = new ConcurrentHashMap<>();

    public YZFMemoryRegionManager(YZFPaths paths){
        this.paths = paths;
        ensureConfig();
        regions.put("YF1", new YZFMemoryRegion("YF1", YZFMemoryRegion.Mode.LOGICAL, 0, 0));
    }

    public Map<String, Object> jvmSnapshot(){
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heapUsed", runtime.totalMemory() - runtime.freeMemory());
        result.put("heapCommitted", runtime.totalMemory());
        result.put("heapMax", runtime.maxMemory());
        result.put("heapFree", runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        result.put("nonHeapUsed", bean.getNonHeapMemoryUsage().getUsed());
        result.put("inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("xms", findJvmArg("-Xms"));
        result.put("xmx", findJvmArg("-Xmx"));
        return result;
    }

    public YZFMemoryRegion create(String id, String mode, long minHeap, long maxHeap){
        if(YZFText.blank(id)) throw new IllegalArgumentException("region id is required");
        String normalized = mode == null ? "logical" : mode.trim().toLowerCase();
        YZFMemoryRegion.Mode selected = switch(normalized){
            case "process" -> YZFMemoryRegion.Mode.PROCESS;
            case "classloader" -> YZFMemoryRegion.Mode.CLASSLOADER;
            default -> YZFMemoryRegion.Mode.LOGICAL;
        };
        YZFMemoryRegion region = new YZFMemoryRegion(id, selected, Math.max(0, minHeap), Math.max(0, maxHeap));
        YZFMemoryRegion old = regions.putIfAbsent(id, region);
        if(old != null) throw new IllegalStateException("memory region already exists: " + id);
        try{
            if(selected == YZFMemoryRegion.Mode.CLASSLOADER) startClassLoader(region);
            else if(selected == YZFMemoryRegion.Mode.PROCESS) startProcess(region);
            else region.start((URLClassLoader)null);
            return region;
        }catch(Throwable error){
            region.fail(error);
            regions.remove(id, region);
            throw new IllegalStateException("failed to create memory region " + id, error);
        }
    }

    private void startClassLoader(YZFMemoryRegion region) throws Exception{
        region.start(new URLClassLoader(new URL[0], getClass().getClassLoader()));
    }

    private void startProcess(YZFMemoryRegion region) throws Exception{
        region.starting();
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        if(System.getProperty("os.name", "").toLowerCase().contains("win")) java += ".exe";
        List<String> command = new ArrayList<>();
        command.add(java);
        if(region.minHeap() > 0) command.add("-Xms" + region.minHeap());
        if(region.maxHeap() > 0) command.add("-Xmx" + region.maxHeap());
        command.add("-cp");
        command.add(resolveCodeSource());
        command.add("mindustry.yzf.YZFRegionProcessMain");
        command.add(region.id());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        region.start(process);
    }

    private String resolveCodeSource(){
        try{ return new File(YZFMemoryRegionManager.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath(); }
        catch(Exception error){ return System.getProperty("java.class.path", ""); }
    }

    public YZFMemoryRegion get(String id){ return regions.get(id); }

    public String loadClassLoaderJar(String regionId, String jarPath, String className) throws Exception{
        YZFMemoryRegion region = regions.get(regionId);
        if(region == null || region.mode() != YZFMemoryRegion.Mode.CLASSLOADER) throw new IllegalArgumentException("classloader region not found: " + regionId);
        File jar = new File(jarPath);
        if(!jar.isFile()) throw new IllegalArgumentException("jar not found: " + jarPath);
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader());
        region.replaceClassLoader(loader);
        if(!YZFText.blank(className)) Class.forName(className, true, loader);
        return className == null ? "" : className;
    }
    public List<Map<String, Object>> list(){
        List<Map<String, Object>> result = new ArrayList<>();
        for(YZFMemoryRegion region : regions.values()) result.add(region.snapshot());
        return result;
    }
    public boolean stop(String id){
        if("YF1".equals(id)) return false;
        YZFMemoryRegion region = regions.remove(id);
        if(region == null) return false;
        region.stop();
        return true;
    }
    public void shutdown(){
        for(String id : new ArrayList<>(regions.keySet())) stop(id);
    }

    public static long parseBytes(String value){
        if(value == null || value.isBlank()) return 0;
        String text = value.trim().toUpperCase();
        long multiplier = 1;
        if(text.endsWith("K")){ multiplier = 1024L; text = text.substring(0, text.length()-1); }
        else if(text.endsWith("M")){ multiplier = 1024L * 1024L; text = text.substring(0, text.length()-1); }
        else if(text.endsWith("G")){ multiplier = 1024L * 1024L * 1024L; text = text.substring(0, text.length()-1); }
        try{ return Math.max(0, Long.parseLong(text.trim()) * multiplier); }catch(NumberFormatException ignored){ return 0; }
    }

    private String findJvmArg(String prefix){
        for(String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) if(arg.startsWith(prefix)) return arg.substring(prefix.length());
        return "unset";
    }

    private void ensureConfig(){
        Fi file = paths.memoryRegionsConfigFile;
        if(file.exists()) return;
        file.parent().mkdirs();
        file.writeString("{\n" +
            "  # YF1 is the original server region and is always present.\n" +
            "  # Plugins may create logical, classloader, or process YF2 regions through the API.\n" +
            "  defaultIsolation: \"classloader\"\n" +
            "  allowPluginCreateRegion: true\n" +
            "}\n");
    }
}
