package mindustry.yzf;

import arc.struct.Seq;

import java.util.Locale;

public final class YZFDriverDefinition{
    public String id;
    public String type = "library";
    public boolean enabled = true;
    public String description = "";
    public String path = "";
    public String driverClassName = "";
    public final Seq<String> files = new Seq<>();
    public final Seq<String> serviceTypes = new Seq<>();

    public boolean supportsServiceType(String serviceType){
        if(serviceTypes.isEmpty()) return true;
        if(YZFText.blank(serviceType)) return false;
        String normalized = serviceType.trim().toLowerCase(Locale.ROOT);
        for(String supported : serviceTypes){
            if(supported != null && normalized.equals(supported.trim().toLowerCase(Locale.ROOT))){
                return true;
            }
        }
        return false;
    }
}
