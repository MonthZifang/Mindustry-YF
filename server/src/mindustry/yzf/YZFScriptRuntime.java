package mindustry.yzf;

import java.nio.file.Path;

public interface YZFScriptRuntime{
    void reloadAll();

    void reloadModule(String moduleId);

    void onFileChange(Path path);

    void shutdown();

    String mode();
}
