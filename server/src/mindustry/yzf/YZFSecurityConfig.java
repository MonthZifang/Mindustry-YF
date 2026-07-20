package mindustry.yzf;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.util.Locale;

public final class YZFSecurityConfig{
    public final boolean allowProcessRuntimes;
    public final boolean auditEnabled;
    private final Seq<String> allowedRuntimes;

    private YZFSecurityConfig(boolean allowProcessRuntimes, boolean auditEnabled, Seq<String> allowedRuntimes){
        this.allowProcessRuntimes = allowProcessRuntimes;
        this.auditEnabled = auditEnabled;
        this.allowedRuntimes = allowedRuntimes;
    }

    public static YZFSecurityConfig load(YZFPaths paths){
        Seq<String> defaults = Seq.with("js", "node", "java", "kt", "kts");
        if(paths == null || !paths.securityFile.exists()){
            return new YZFSecurityConfig(true, true, defaults);
        }

        try{
            Jval root = Jval.read(YZFText.readTextSmart(paths.securityFile));
            Seq<String> allowed = new Seq<>();
            if(root.has("allowedRuntimes") && root.get("allowedRuntimes").isArray()){
                for(Jval child : root.get("allowedRuntimes").asArray()){
                    if(!child.isString()) continue;
                    String runtime = child.asString().trim().toLowerCase(Locale.ROOT);
                    if(YZFSecurity.validRuntime(runtime) && !allowed.contains(runtime)){
                        allowed.add(runtime);
                    }
                }
            }else{
                allowed.addAll(defaults);
            }
            return new YZFSecurityConfig(
                root.getBool("allowProcessRuntimes", false),
                root.getBool("auditEnabled", true),
                allowed
            );
        }catch(Throwable t){
            Log.err("[@] Failed to load security configuration: @", MindustryYZF.name, paths.securityFile.absolutePath(), t);
            return new YZFSecurityConfig(true, true, defaults);
        }
    }

    public boolean allows(String runtime){
        if(YZFText.blank(runtime)) return false;
        return allowedRuntimes.contains(runtime.trim().toLowerCase(Locale.ROOT));
    }

    public boolean allowsProcess(String runtime){
        return allowProcessRuntimes && allows(runtime);
    }

    public Seq<String> allowedRuntimes(){
        return allowedRuntimes.copy();
    }
}
