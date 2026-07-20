package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;

public final class YZFModuleRegistry{
    private final Fi modulesDir;
    private final Fi pluginsDir;
    private final Fi scriptsDir;
    private final Seq<YZFModuleDefinition> modules = new Seq<>();
    private final ObjectMap<String, YZFModuleDefinition> byId = new ObjectMap<>();
    private final Seq<String> dependencyErrors = new Seq<>();
    private final Seq<String> dependencyWarnings = new Seq<>();

    public YZFModuleRegistry(Fi modulesDir, Fi pluginsDir, Fi scriptsDir){
        this.modulesDir = modulesDir;
        this.pluginsDir = pluginsDir;
        this.scriptsDir = scriptsDir;
    }

    public synchronized void scan(){
        modules.clear();
        byId.clear();
        dependencyErrors.clear();
        dependencyWarnings.clear();

        Seq<YZFModuleDefinition> discovered = new Seq<>();
        if(modulesDir.exists()){
            for(Fi authorDir : modulesDir.list()){
                if(!authorDir.isDirectory()) continue;
                for(Fi moduleRoot : authorDir.list()){
                    if(!moduleRoot.isDirectory()) continue;
                    YZFModuleDefinition module = YZFModuleLoader.loadModule(moduleRoot);
                    if(module != null){
                        discovered.add(module);
                    }
                }
            }
        }

        // 扫描 plugins/ 目录（平级结构，不需要 author 子目录）
        if(pluginsDir.exists()){
            for(Fi pluginRoot : pluginsDir.list()){
                if(!pluginRoot.isDirectory()) continue;
                YZFModuleDefinition module = YZFModuleLoader.loadModule(pluginRoot);
                if(module != null){
                    // 标记为插件来源
                    module.meta._source = "plugins";
                    discovered.add(module);
                }
            }
        }

        YZFDependencyResolver.Resolution resolution = new YZFDependencyResolver().resolve(discovered);
        dependencyErrors.addAll(resolution.errors);
        dependencyWarnings.addAll(resolution.warnings);
        for(String error : dependencyErrors){
            Log.err("[@] @", MindustryYZF.name, error);
        }
        for(String warning : dependencyWarnings){
            Log.warn("[@] @", MindustryYZF.name, warning);
        }

        for(YZFModuleDefinition module : resolution.ordered){
            if(hasMissingHardDependency(module)) continue;
            register(module);
        }

        Log.info("[@] 模块注册表扫描完成。模块数=@ 脚本数=@", MindustryYZF.name, moduleCount(), scriptCount());
    }

    public synchronized Seq<YZFModuleDefinition> modules(){
        return modules.copy();
    }

    public synchronized YZFModuleDefinition find(String id){
        YZFModuleDefinition direct = byId.get(id);
        if(direct != null) return direct;

        YZFModuleDefinition found = null;
        for(YZFModuleDefinition module : modules){
            if(module.id().equals(id)){
                if(found != null) return null;
                found = module;
            }
        }
        return found;
    }

    public synchronized int moduleCount(){
        return modules.size;
    }

    public synchronized int scriptCount(){
        int total = 0;
        for(YZFModuleDefinition module : modules){
            total += module.scripts.size;
        }
        return total;
    }

    public synchronized Seq<String> dependencyErrors(){
        return dependencyErrors.copy();
    }

    public synchronized Seq<String> dependencyWarnings(){
        return dependencyWarnings.copy();
    }

    public synchronized YZFReloadSnapshot snapshot(Seq<YZFModuleDefinition> targets, Seq<String> loadedIds){
        return new YZFReloadSnapshot(targets.copy(), loadedIds.copy());
    }

    public synchronized Seq<YZFModuleDefinition> resolveReloadPlan(String moduleId){
        YZFModuleDefinition root = find(moduleId);
        if(root == null) return new Seq<>();

        Seq<YZFModuleDefinition> affected = new Seq<>();
        collectDependents(root.fullId(), affected);

        Seq<YZFModuleDefinition> orderedPlan = new Seq<>();
        for(YZFModuleDefinition module : modules){
            if(affected.contains(module)){
                orderedPlan.add(module);
            }
        }
        return orderedPlan;
    }

    private void register(YZFModuleDefinition module){
        modules.add(module);
        byId.put(module.fullId(), module);
    }

    private boolean hasMissingHardDependency(YZFModuleDefinition module){
        for(String depend : module.meta.depends){
            if(byId.get(depend) == null){
                return true;
            }
        }
        return false;
    }

    private void collectDependents(String moduleId, Seq<YZFModuleDefinition> affected){
        YZFModuleDefinition module = byId.get(moduleId);
        if(module == null) return;
        if(!affected.contains(module)){
            affected.add(module);
        }

        for(YZFModuleDefinition candidate : modules){
            if(candidate == module) continue;
            if(candidate.meta.depends.contains(moduleId) || candidate.meta.softDepends.contains(moduleId)){
                collectDependents(candidate.fullId(), affected);
            }
        }
    }
}
