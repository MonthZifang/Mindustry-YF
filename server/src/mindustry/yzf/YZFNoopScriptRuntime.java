package mindustry.yzf;

import arc.util.Log;

import java.nio.file.Path;

public final class YZFNoopScriptRuntime implements YZFScriptRuntime{
    private final YZFModuleRegistry registry;

    public YZFNoopScriptRuntime(YZFModuleRegistry registry){
        this.registry = registry;
    }

    @Override
    public void reloadAll(){
        registry.scan();
        Log.info("[@] 基础运行时已执行全量重载，当前仅刷新元数据。", MindustryYZF.name);
    }

    @Override
    public void reloadModule(String moduleId){
        registry.scan();
        Log.info("[@] 基础运行时已重载模块 '@'，当前仅刷新元数据。", MindustryYZF.name, moduleId);
    }

    @Override
    public void onFileChange(Path path){
        registry.scan();
        Log.info("[@] 检测到文件变化: @", MindustryYZF.name, path);
    }

    @Override
    public void shutdown(){
        //nothing to dispose in the foundation runtime
    }

    @Override
    public String mode(){
        return "基础占位";
    }
}
