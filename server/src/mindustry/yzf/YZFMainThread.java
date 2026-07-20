package mindustry.yzf;

import arc.Core;

public final class YZFMainThread{
    private YZFMainThread(){
    }

    public static void post(Runnable action){
        if(action == null) return;
        if(Core.app != null){
            Core.app.post(action);
        }else{
            action.run();
        }
    }
}
