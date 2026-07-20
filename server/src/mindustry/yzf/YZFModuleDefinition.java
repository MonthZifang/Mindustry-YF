package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;

public final class YZFModuleDefinition{
    public final YZFModuleMeta meta;
    public final Fi root;
    public final Fi metaFile;
    public final Fi scriptsDir;
    public final Fi dataDir;
    public final Fi cacheDir;
    public final Fi mainScript;
    public final Seq<Fi> scripts = new Seq<>();

    public YZFModuleDefinition(YZFModuleMeta meta, Fi root, Fi metaFile, Fi scriptsDir, Fi dataDir, Fi cacheDir, Fi mainScript){
        this.meta = meta;
        this.root = root;
        this.metaFile = metaFile;
        this.scriptsDir = scriptsDir;
        this.dataDir = dataDir;
        this.cacheDir = cacheDir;
        this.mainScript = mainScript;
    }

    public String id(){
        return meta.id;
    }

    public String author(){
        return meta.author;
    }

    public String fullId(){
        return author() + "/" + id();
    }

    public boolean hasMain(){
        return mainScript != null && mainScript.exists() && !mainScript.isDirectory();
    }
}
