package mindustry.yzf;

public final class YZFSecurity{
    private YZFSecurity(){
    }

    public static boolean validCommandName(String value){
        return value != null && value.matches("[a-z0-9_-]+");
    }

    public static boolean validIdentifier(String value){
        return value != null && value.matches("[A-Za-z0-9._/-]+");
    }

    public static boolean validRuntime(String runtime){
        return runtime != null && (runtime.equalsIgnoreCase("js") || runtime.equalsIgnoreCase("java") || runtime.equalsIgnoreCase("kt") || runtime.equalsIgnoreCase("kts") || runtime.equalsIgnoreCase("node"));
    }

    public static boolean validPermission(String value){
        return value != null && value.matches("[a-z0-9._*-]+");
    }

    public static String mask(String value){
        if(YZFText.blank(value)) return "<空>";
        if(value.length() <= 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    public static String sanitizeLog(String value){
        if(value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
