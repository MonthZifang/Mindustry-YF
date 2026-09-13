package mindustry.server;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.net.NetworkIO;

import java.nio.charset.StandardCharsets;

/** Controls the build label advertised in server-list ping responses. */
public final class ServerBrandingConfig{
    private static final int fallbackBuild = 160;
    private static volatile String status = "尚未加载";

    private ServerBrandingConfig(){ }

    public static void apply(){
        Fi file = Vars.dataDirectory.child("yzf").child("server-branding.hjson");
        if(!file.exists()) file.writeString(defaultText());

        try{
            Jval root = Jval.read(file.readString());
            boolean enabled = root.getBool("enabled", true);
            if(!enabled){
                NetworkIO.clearServerVersionAdvertisement();
                status = "enabled=false（使用原始版本信息）";
                Log.info("[YZF] 服务器列表品牌配置已关闭。");
                return;
            }

            String label = root.getString("label", "MindustryYF").trim();
            if(label.isEmpty()) label = "MindustryYF";
            label = limitUtf8(label, 32);

            int defaultBuild = Version.build > 0 ? Version.build : fallbackBuild;
            int build = root.getInt("build", defaultBuild);
            if(build <= 0) build = defaultBuild;

            NetworkIO.setServerVersionAdvertisement(build, label);
            status = "enabled=true build=" + build + " label=" + label;
            Log.info("[YZF] 服务器列表品牌配置已应用: @", status);
        }catch(Throwable error){
            NetworkIO.clearServerVersionAdvertisement();
            status = "配置错误，已回退原始版本信息";
            Log.err("[YZF] 服务器列表品牌配置解析失败: @", error);
        }
    }

    public static String status(){
        return status;
    }

    public static String defaultText(){
        return "# YZF 服务器列表品牌配置。修改后执行 yzf branding reload。\n" +
            "# 原版客户端会显示为: v<build> <label>\n" +
            "# build 必须与玩家客户端的主构建号一致；160.3 对应 160。\n" +
            "enabled: true\n" +
            "label: \"MindustryYF\"\n" +
            "build: 160\n";
    }

    private static String limitUtf8(String value, int maxBytes){
        String result = value;
        while(result.getBytes(StandardCharsets.UTF_8).length > maxBytes && !result.isEmpty()){
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        }
        return result.isEmpty() ? "MindustryYF" : result;
    }
}
