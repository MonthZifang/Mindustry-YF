package mindustry.yzf;

import arc.struct.Seq;

import java.net.URLClassLoader;

public final class YZFDriverHandle{
    public final YZFDriverDefinition definition;
    public final URLClassLoader loader;
    public final Seq<String> jarPaths = new Seq<>();

    public YZFDriverHandle(YZFDriverDefinition definition, URLClassLoader loader, Seq<String> jarPaths){
        this.definition = definition;
        this.loader = loader;
        if(jarPaths != null){
            this.jarPaths.addAll(jarPaths);
        }
    }
}
