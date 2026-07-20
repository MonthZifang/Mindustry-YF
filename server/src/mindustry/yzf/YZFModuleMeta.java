package mindustry.yzf;

import arc.struct.Seq;

public final class YZFModuleMeta{
    public String id;
    public String name;
    public String author = "unknown";
    public String description = "";
    public String version = "0.1.0";
    public String main = "scripts/main.js";
    public String runtime = "js";
    public boolean enabled = true;
    public boolean hidden;
    public boolean requiresArgs;
    public String category = "Runtime";
    public String permission = "";
    public final Seq<String> tags = new Seq<>();
    public final Seq<String> depends = new Seq<>();
    public final Seq<String> softDepends = new Seq<>();
    public final Seq<String> jvmArgs = new Seq<>();
    public final Seq<String> programArgs = new Seq<>();
    /** Per-process memory policy. Values accept bytes, K, M or G (for example 256M). */
    public String memoryMin = "";
    public String memoryMax = "";
    /** 加载方式: "module" 或 "plugin"，由 module.hjson 中的 loadType 指定 */
    public String loadType = "module";
    /** 来源: "modules" 或 "plugins"，扫描时根据 loadType 或目录自动设置 */
    public transient String _source = "modules";
}
