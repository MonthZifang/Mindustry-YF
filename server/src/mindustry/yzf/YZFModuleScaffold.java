package mindustry.yzf;

import arc.files.Fi;

public final class YZFModuleScaffold{
    private YZFModuleScaffold(){
    }

    public static YZFModuleDefinition create(YZFPaths paths, String author, String moduleId, String displayName){
        String normalizedAuthor = sanitize(author);
        String normalizedModuleId = sanitize(moduleId);
        String finalDisplay = YZFText.blank(displayName) ? normalizedModuleId : displayName.trim();

        Fi authorDir = paths.modulesDir.child(normalizedAuthor);
        Fi moduleRoot = authorDir.child(normalizedModuleId);
        Fi scriptsDir = moduleRoot.child("scripts");
        Fi dataDir = moduleRoot.child("data");
        Fi cacheDir = moduleRoot.child("cache");
        Fi metaFile = moduleRoot.child("module.hjson");
        Fi mainScript = scriptsDir.child("main.js");
        Fi readmeFile = moduleRoot.child("README.md");

        authorDir.mkdirs();
        moduleRoot.mkdirs();
        scriptsDir.mkdirs();
        dataDir.mkdirs();
        cacheDir.mkdirs();

        if(!metaFile.exists()){
            metaFile.writeString(
                "name: " + finalDisplay + "\n" +
                "id: " + normalizedModuleId + "\n" +
                "author: " + normalizedAuthor + "\n" +
                "description: \"MindustryYZF JavaScript 服务端模块\"\n" +
                "version: \"0.1.0\"\n" +
                "main: \"scripts/main.js\"\n" +
                "runtime: \"js\"\n" +
                "memoryMin: \"\"\n" +
                "memoryMax: \"\"\n" +
                "enabled: true\n" +
                "hidden: false\n" +
                "requiresArgs: false\n" +
                "category: \"Runtime\"\n" +
                "permission: \"\"\n" +
                "tags: [yzf]\n" +
                "depends: []\n" +
                "softDepends: []\n" +
                "jvmArgs: []\n" +
                "programArgs: []\n"
            );
        }

        if(!mainScript.exists()){
            mainScript.writeString(
                "// MindustryYZF JavaScript 服务端模块入口\n" +
                "yzf.onEnable(function(){\n" +
                "  yzf.info(\"模块已启用: \" + yzfModule.fullId);\n" +
                "});\n\n" +
                "yzf.onDisable(function(){\n" +
                "  yzf.info(\"模块已停用: \" + yzfModule.fullId);\n" +
                "});\n\n" +
                "yzf.command(\"" + normalizedModuleId + "-ping\", \"简单测试命令\", function(args){\n" +
                "  yzf.info(\"收到 " + normalizedModuleId + "-ping，参数数量=\" + args.length);\n" +
                "});\n"
            );
        }

        if(!readmeFile.exists()){
            readmeFile.writeString(
                "# " + finalDisplay + "\n\n" +
                "- 作者目录: " + normalizedAuthor + "\n" +
                "- 模块 ID: " + normalizedModuleId + "\n" +
                "- 完整 ID: " + normalizedAuthor + "/" + normalizedModuleId + "\n" +
                "- 运行时: js\n" +
                "- 入口脚本: scripts/main.js\n"
            );
        }

        YZFModuleMeta meta = new YZFModuleMeta();
        meta.id = normalizedModuleId;
        meta.name = finalDisplay;
        meta.author = normalizedAuthor;
        meta.main = "scripts/main.js";
        meta.runtime = "js";
        meta.category = "Runtime";

        return new YZFModuleDefinition(meta, moduleRoot, metaFile, scriptsDir, dataDir, cacheDir, mainScript);
    }

    private static String sanitize(String input){
        if(YZFText.blank(input)) return "unknown";
        return input.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
    }
}
