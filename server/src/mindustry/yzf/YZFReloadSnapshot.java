package mindustry.yzf;

import arc.struct.Seq;

public final class YZFReloadSnapshot{
    public final Seq<YZFModuleDefinition> modules;
    public final Seq<String> loadedIds;

    public YZFReloadSnapshot(Seq<YZFModuleDefinition> modules, Seq<String> loadedIds){
        this.modules = modules;
        this.loadedIds = loadedIds;
    }
}
