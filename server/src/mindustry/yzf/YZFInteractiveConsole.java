package mindustry.yzf;

import arc.util.Log;
import mindustry.server.ServerControl;

public final class YZFInteractiveConsole{
    private final ServerControl serverControl;
    private final Runnable fallbackInput;

    public YZFInteractiveConsole(ServerControl serverControl, Runnable fallbackInput){
        this.serverControl = serverControl;
        this.fallbackInput = fallbackInput;
    }

    public void run(){
        Log.info("[@] 后端动态交互页面已停用，已回退到原生命令行输入模式。", MindustryYZF.name);
        fallbackInput.run();
    }

    public ServerControl serverControl(){
        return serverControl;
    }
}
