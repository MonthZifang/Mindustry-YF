package mindustry.yzf;

import arc.util.serialization.Jval;

public final class YZFResponse{
    private YZFResponse(){
    }

    public static Jval ok(String code, String message){
        return build(true, code, message, null);
    }

    public static Jval ok(String code, String message, Jval data){
        return build(true, code, message, data);
    }

    public static Jval fail(String code, String message){
        return build(false, code, message, null);
    }

    public static Jval fail(String code, String message, Jval data){
        return build(false, code, message, data);
    }

    public static Jval build(boolean ok, String code, String message, Jval data){
        Jval root = Jval.newObject();
        root.put("ok", ok);
        root.put("success", ok);
        root.put("code", YZFText.blank(code) ? (ok ? "ok" : "error") : code);
        root.put("message", message == null ? "" : message);
        root.put("timestampMs", System.currentTimeMillis());
        if(data != null){
            root.put("data", data);
        }
        return root;
    }
}
