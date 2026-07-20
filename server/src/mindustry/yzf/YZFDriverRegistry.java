package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class YZFDriverRegistry{
    private final YZFPaths paths;
    private final ObjectMap<String, YZFDriverDefinition> definitions = new ObjectMap<>();
    private final ObjectMap<String, YZFDriverHandle> handles = new ObjectMap<>();

    public YZFDriverRegistry(YZFPaths paths){
        this.paths = paths;
        reload();
    }

    public synchronized void reload(){
        closeHandles();
        definitions.clear();
        handles.clear();
        loadDefinitions();
    }

    public synchronized void shutdown(){
        closeHandles();
        handles.clear();
    }

    public synchronized YZFDriverHandle require(YZFServiceConfig config){
        String driverId = resolveDriverId(config);
        if(YZFText.blank(driverId)){
            throw new IllegalStateException("No external driver mapping for service type: " + config.type);
        }

        YZFDriverDefinition definition = definitions.get(driverId);
        if(definition == null){
            throw new IllegalStateException("Driver index entry not found: " + driverId + " (" + paths.driverRegistryFile.absolutePath() + ")");
        }
        if(!definition.enabled){
            throw new IllegalStateException("Driver entry is disabled: " + driverId);
        }
        if(!definition.supportsServiceType(config.type)){
            throw new IllegalStateException("Driver " + driverId + " does not support service type " + config.type);
        }

        YZFDriverHandle existing = handles.get(driverId);
        if(existing != null) return existing;

        Seq<Fi> jars = collectJars(definition);
        if(jars.isEmpty()){
            throw new IllegalStateException("No JAR files found for driver " + driverId + " under " + paths.driversDir.absolutePath());
        }

        try{
            URL[] urls = new URL[jars.size];
            Seq<String> resolvedPaths = new Seq<>();
            for(int i = 0; i < jars.size; i++){
                urls[i] = jars.get(i).file().toURI().toURL();
                resolvedPaths.add(jars.get(i).absolutePath());
            }
            URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
            YZFDriverHandle handle = new YZFDriverHandle(definition, loader, resolvedPaths);
            handles.put(driverId, handle);
            return handle;
        }catch(Exception e){
            throw new IllegalStateException("Failed to open driver loader for " + driverId, e);
        }
    }

    public synchronized String resolveDriverId(YZFServiceConfig config){
        if(config == null) return null;
        if(!YZFText.blank(config.driverId)) return config.driverId.trim();
        return defaultDriverId(config.type);
    }

    public synchronized String defaultDriverId(String serviceType){
        if(YZFText.blank(serviceType)) return null;
        return switch(serviceType.trim().toLowerCase(Locale.ROOT)){
            case "mysql" -> "mysql-default";
            case "mariadb" -> "mariadb-default";
            case "postgresql", "postgres" -> "postgresql-default";
            case "redis" -> "redis-default";
            case "minio" -> "minio-default";
            default -> null;
        };
    }

    private void loadDefinitions(){
        if(!paths.driverRegistryFile.exists()) return;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(paths.driverRegistryFile));
            Jval items = root == null ? null : root.get("drivers");
            if(items == null || !items.isArray()) return;
            for(Jval item : items.asArray()){
                if(item == null || !item.isObject()) continue;
                YZFDriverDefinition definition = new YZFDriverDefinition();
                definition.id = item.getString("id", null);
                definition.type = item.getString("type", definition.type);
                definition.enabled = item.getBool("enabled", definition.enabled);
                definition.description = item.getString("description", definition.description);
                definition.path = item.getString("path", definition.path);
                definition.driverClassName = item.getString("driverClassName", definition.driverClassName);
                if(item.has("files") && item.get("files").isArray()){
                    for(Jval file : item.get("files").asArray()){
                        if(file != null && file.isString()) definition.files.add(file.asString());
                    }
                }
                if(item.has("serviceTypes") && item.get("serviceTypes").isArray()){
                    for(Jval type : item.get("serviceTypes").asArray()){
                        if(type != null && type.isString()) definition.serviceTypes.add(type.asString());
                    }
                }
                if(!YZFText.blank(definition.id)){
                    definitions.put(definition.id, definition);
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to load external driver index: @", MindustryYZF.name, paths.driverRegistryFile.absolutePath(), e);
        }
    }

    private Seq<Fi> collectJars(YZFDriverDefinition definition){
        Set<String> orderedPaths = new LinkedHashSet<>();
        Seq<Fi> jars = new Seq<>();

        if(!YZFText.blank(definition.path)){
            collectFromPath(resolveDriverFile(definition.path), orderedPaths, jars);
        }
        for(String file : definition.files){
            collectFromPath(resolveDriverFile(file), orderedPaths, jars);
        }
        return jars;
    }

    private void collectFromPath(Fi target, Set<String> orderedPaths, Seq<Fi> jars){
        if(target == null || !target.exists()) return;
        if(target.isDirectory()){
            for(Fi child : target.list()){
                collectFromPath(child, orderedPaths, jars);
            }
            return;
        }
        if(!target.extEquals("jar")) return;

        String absolutePath = target.absolutePath();
        if(orderedPaths.add(absolutePath)){
            jars.add(target);
        }
    }

    private Fi resolveDriverFile(String value){
        if(YZFText.blank(value)) return null;
        File file = new File(value);
        if(file.isAbsolute()){
            return new Fi(file.getAbsolutePath());
        }
        return paths.driversDir.child(value);
    }

    private void closeHandles(){
        for(YZFDriverHandle handle : handles.values()){
            try{
                handle.loader.close();
            }catch(Exception ignored){
            }
        }
    }
}
