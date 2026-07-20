package mindustry.yzf;

public final class YZFCallbackGuard{
    private YZFCallbackGuard(){}

    public static boolean run(String moduleId, String kind, Runnable callback){
        if(callback == null) return true;
        YZFContext context = MindustryYZF.context();
        if(context == null || MindustryYZF.isShuttingDown()) return false;
        try{
            callback.run();
            return true;
        }catch(Throwable error){
            context.metrics.callbackFailures.incrementAndGet();
            context.metrics.markFailure("callback:" + moduleId + ":" + kind + ":" + error.getMessage());
            YZFErrorLog.medium(moduleId, "Callback failed: " + kind, error);
            return false;
        }
    }
}
