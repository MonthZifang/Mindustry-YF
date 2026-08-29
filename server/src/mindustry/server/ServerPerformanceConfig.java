package mindustry.server;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.core.PerformanceSettings;

import java.util.Locale;

/** Loads the optional YZF server performance profile. */
public final class ServerPerformanceConfig{
    private static volatile String status = "尚未加载";

    private ServerPerformanceConfig(){ }

    public static void apply(){
        Fi file = Vars.dataDirectory.child("yzf").child("performance.hjson");
        if(!file.exists()) file.writeString(defaultText());
        try{
            Jval root = Jval.read(file.readString());
            String preset = root.getString("preset", "off").trim().toLowerCase(Locale.ROOT);
            if(!preset.equals("off") && !preset.equals("balanced") && !preset.equals("aggressive") && !preset.equals("custom")) preset = "off";
            boolean enabled = root.getBool("enabled", !preset.equals("off"));
            int maxFires = 0, unitLimit = 0, enemyUnitLimit = 0;
            float fireInterval = 1f;
            if(enabled){
                if(preset.equals("balanced")){
                    maxFires = 1200; fireInterval = 1.25f;
                }else if(preset.equals("aggressive")){
                    maxFires = 300; fireInterval = 2f;
                }else if(preset.equals("custom")){
                    Jval custom = root.has("custom") && root.get("custom").isObject() ? root.get("custom") : root;
                    fireInterval = Math.max(0.25f, custom.getFloat("fireDamageIntervalMultiplier", 1f));
                    maxFires = Math.max(0, custom.getInt("fireLimit", 0));
                    unitLimit = Math.max(0, custom.getInt("unitLimit", 0));
                    enemyUnitLimit = Math.max(0, custom.getInt("enemyUnitLimit", 0));
                }
            }
            PerformanceSettings.reset();
            PerformanceSettings.fireLimit = maxFires;
            PerformanceSettings.fireDamageIntervalMultiplier = fireInterval;
            PerformanceSettings.unitLimit = unitLimit;
            PerformanceSettings.enemyUnitLimit = enemyUnitLimit;
            status = "preset=" + preset + " enabled=" + enabled + " fireLimit=" + maxFires + " unitLimit=" + unitLimit + " enemyUnitLimit=" + enemyUnitLimit;
            Log.info("[YZF] 性能配置已应用: @", status);
        }catch(Throwable error){
            Log.err("[YZF] 性能配置解析失败，使用默认设置: @", error);
            PerformanceSettings.reset();
            status = "配置错误，已回退默认值";
        }
    }

    public static String status(){ return status; }

    /** Public integration API for plugins and external modules. A value of 0 disables the limit. */
    public static int unitLimit(){ return PerformanceSettings.unitLimit; }
    public static int enemyUnitLimit(){ return PerformanceSettings.enemyUnitLimit; }
    public static void setUnitLimits(int friendly, int enemy){
        PerformanceSettings.unitLimit = Math.max(0, friendly);
        PerformanceSettings.enemyUnitLimit = Math.max(0, enemy);
    }
    public static int unitLimit(int teamId){ return PerformanceSettings.unitLimit(mindustry.game.Team.get(teamId)); }
    public static void setTeamUnitLimit(int teamId, int limit){ PerformanceSettings.setTeamUnitLimit(teamId, limit); }
    public static int fireLimit(){ return PerformanceSettings.fireLimit; }
    public static void setFireLimit(int limit){ PerformanceSettings.fireLimit = Math.max(0, limit); }

    public static String defaultText(){
        return "# YZF 服务端性能增强配置（重启生效）\n" +
            "# preset: off / balanced / aggressive / custom\n" +
            "enabled: false\n" +
            "preset: \"balanced\"\n" +
            "custom: {\n" +
            "  fireLimit: 0\n" +
            "  unitLimit: 0\n" +
            "  enemyUnitLimit: 0\n" +
            "  fireDamageIntervalMultiplier: 1.0\n" +
            "}\n";
    }
}
