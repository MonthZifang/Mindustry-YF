package mindustry.yzf;

import arc.util.serialization.Jval;

import java.util.LinkedHashMap;
import java.util.Map;

/** 页面必须由插件主动注册；Adapter 只消费这里的注册结果。 */
public final class YZFWebUiRegistry{
    private final Map<String, Jval> pages = new LinkedHashMap<>();

    public synchronized void register(String moduleId, String pageId, String descriptorJson){
        if(YZFText.blank(moduleId) || YZFText.blank(pageId)) throw new IllegalArgumentException("moduleId/pageId cannot be blank");
        Jval descriptor = Jval.read(descriptorJson == null ? "{}" : descriptorJson);
        if(descriptor == null || !descriptor.isObject()) throw new IllegalArgumentException("page descriptor must be a JSON object");
        String localId = pageId.trim();
        String key = moduleId + ":" + localId;
        descriptor.put("id", key);
        descriptor.put("pageId", localId);
        descriptor.put("pluginId", moduleId);
        descriptor.put("title", descriptor.getString("title", localId));
        descriptor.put("group", descriptor.getString("group", moduleId));
        descriptor.put("order", descriptor.getInt("order", 100));
        pages.put(key, descriptor);
    }

    public synchronized boolean unregister(String moduleId, String pageId){
        return pages.remove(moduleId + ":" + pageId) != null;
    }

    public synchronized void unregisterModule(String moduleId){
        pages.entrySet().removeIf(entry -> entry.getValue().getString("pluginId", "").equals(moduleId));
    }

    public synchronized Jval find(String id){
        Jval page = pages.get(id);
        return page == null ? null : Jval.read(page.toString(Jval.Jformat.plain));
    }

    public synchronized String listJson(){
        Jval root = Jval.newObject();
        root.put("ok", true);
        Jval result = Jval.newArray();
        for(Jval page : pages.values()) result.add(Jval.read(page.toString(Jval.Jformat.plain)));
        root.put("pages", result);
        return root.toString(Jval.Jformat.plain);
    }
}
