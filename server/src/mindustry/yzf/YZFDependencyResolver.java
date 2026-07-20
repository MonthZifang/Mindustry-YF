package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;

public final class YZFDependencyResolver{
    private final ObjectMap<String, YZFModuleDefinition> modulesById = new ObjectMap<>();
    private final ObjectSet<String> tempMarks = new ObjectSet<>();
    private final ObjectSet<String> permMarks = new ObjectSet<>();
    private final Seq<YZFModuleDefinition> ordered = new Seq<>();
    private final Seq<String> errors = new Seq<>();
    private final Seq<String> warnings = new Seq<>();

    public Resolution resolve(Seq<YZFModuleDefinition> discovered){
        modulesById.clear();
        tempMarks.clear();
        permMarks.clear();
        ordered.clear();
        errors.clear();
        warnings.clear();

        for(YZFModuleDefinition module : discovered){
            modulesById.put(module.fullId(), module);
        }

        for(YZFModuleDefinition module : discovered){
            visit(module, new Seq<>());
        }

        return new Resolution(ordered.copy(), errors.copy(), warnings.copy());
    }

    private void visit(YZFModuleDefinition module, Seq<String> stack){
        String id = module.fullId();
        if(permMarks.contains(id)) return;
        if(tempMarks.contains(id)){
            Seq<String> cycle = stack.copy();
            cycle.add(id);
            errors.add("检测到循环依赖: " + String.join(" -> ", cycle.toArray(String.class)));
            return;
        }

        tempMarks.add(id);
        stack.add(id);
        for(String depend : module.meta.depends){
            YZFModuleDefinition target = modulesById.get(depend);
            if(target == null){
                errors.add("模块 " + id + " 缺少硬依赖 " + depend);
            }else{
                visit(target, stack);
            }
        }
        for(String depend : module.meta.softDepends){
            YZFModuleDefinition target = modulesById.get(depend);
            if(target == null){
                warnings.add("模块 " + id + " 缺少软依赖 " + depend);
            }else{
                visit(target, stack);
            }
        }
        stack.pop();
        tempMarks.remove(id);
        permMarks.add(id);
        if(!ordered.contains(module)){
            ordered.add(module);
        }
    }

    public static final class Resolution{
        public final Seq<YZFModuleDefinition> ordered;
        public final Seq<String> errors;
        public final Seq<String> warnings;

        public Resolution(Seq<YZFModuleDefinition> ordered, Seq<String> errors, Seq<String> warnings){
            this.ordered = ordered;
            this.errors = errors;
            this.warnings = warnings;
        }
    }
}
