package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;

/**
 * 内容注册系统。
 * 1. 允许模块注册自定义内容元数据（JSON），持久化存储。
 * 2. 允许修改已有内容的属性（通过反射）。
 * 注意：运行时注册新内容（如新方块/新物品）需要客户端同步，仅修改属性是服务器端的。
 */
public final class YZFContentRegistry{
    // namespace -> {name -> json}
    private final ObjectMap<String, ObjectMap<String, String>> metaStore = new ObjectMap<>();
    private final File dataFile;

    public YZFContentRegistry(File dataDir){
        dataFile = new File(dataDir, "content-registry.json");
        dataDir.mkdirs();
        load();
    }

    /**
     * 注册自定义内容元数据
     * @param namespace 命名空间（模块ID或自定义）
     * @param name 内容名称
     * @param json JSON 字符串
     */
    public void registerMeta(String namespace, String name, String json){
        ObjectMap<String, String> ns = metaStore.get(namespace);
        if(ns == null){
            ns = new ObjectMap<>();
            metaStore.put(namespace, ns);
        }
        ns.put(name, json);
        save();
        Log.info("[@] 内容元数据注册: @:@", MindustryYZF.name, namespace, name);
    }

    /**
     * 获取自定义内容元数据
     */
    public String getMeta(String namespace, String name){
        ObjectMap<String, String> ns = metaStore.get(namespace);
        if(ns == null) return null;
        return ns.get(name);
    }

    /**
     * 列出某个命名空间下的所有内容名称
     */
    public String listMeta(String namespace){
        ObjectMap<String, String> ns = metaStore.get(namespace);
        Jval array = Jval.newArray();
        if(ns != null){
            for(String key : ns.keys()){
                array.add(key);
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    /**
     * 列出所有命名空间
     */
    public String listNamespaces(){
        Jval array = Jval.newArray();
        for(String ns : metaStore.keys()){
            array.add(ns);
        }
        return array.toString(Jval.Jformat.plain);
    }

    /**
     * 删除自定义内容元数据
     */
    public boolean removeMeta(String namespace, String name){
        ObjectMap<String, String> ns = metaStore.get(namespace);
        if(ns == null) return false;
        String removed = ns.remove(name);
        if(removed != null){
            save();
            return true;
        }
        return false;
    }

    /**
     * 修改已有内容的属性（通过反射）。
     * 这是服务器端修改，不会同步到客户端。
     * @param contentName 内容名称（如 "duo", "copper"）
     * @param property 属性名（如 "health", "size"）
     * @param value 值（字符串，会自动转换类型）
     * @return JSON 结果 {success, message}
     */
    public String setProperty(String contentName, String property, String value){
        Jval result = Jval.newObject();
        try{
            // 尝试在各种内容类型中查找
            mindustry.ctype.Content content = findContent(contentName);
            if(content == null){
                result.put("success", false);
                result.put("message", "找不到内容: " + contentName);
                return result.toString(Jval.Jformat.plain);
            }

            // 通过反射设置属性
            Field field = findField(content.getClass(), property);
            if(field == null){
                result.put("success", false);
                result.put("message", "找不到属性: " + property + " (在 " + content.getClass().getSimpleName() + " 中)");
                return result.toString(Jval.Jformat.plain);
            }

            field.setAccessible(true);
            Object converted = convertValue(field.getType(), value);
            field.set(content, converted);

            result.put("success", true);
            result.put("message", "已设置 " + contentName + "." + property + " = " + value);
        }catch(Exception e){
            result.put("success", false);
            result.put("message", "错误: " + e.getMessage());
        }
        return result.toString(Jval.Jformat.plain);
    }

    /**
     * 获取已有内容的属性值
     */
    public String getProperty(String contentName, String property){
        try{
            mindustry.ctype.Content content = findContent(contentName);
            if(content == null) return null;

            Field field = findField(content.getClass(), property);
            if(field == null) return null;

            field.setAccessible(true);
            Object val = field.get(content);
            return val != null ? String.valueOf(val) : null;
        }catch(Exception e){
            return null;
        }
    }

    private mindustry.ctype.Content findContent(String name){
        // 按优先级尝试各种内容类型
        mindustry.ctype.Content c;
        c = mindustry.Vars.content.block(name);
        if(c != null) return c;
        c = mindustry.Vars.content.item(name);
        if(c != null) return c;
        c = mindustry.Vars.content.liquid(name);
        if(c != null) return c;
        c = mindustry.Vars.content.unit(name);
        if(c != null) return c;
        c = mindustry.Vars.content.statusEffect(name);
        if(c != null) return c;
        c = mindustry.Vars.content.weather(name);
        if(c != null) return c;
        c = mindustry.Vars.content.planet(name);
        if(c != null) return c;
        return null;
    }

    private Field findField(Class<?> clazz, String name){
        Class<?> c = clazz;
        while(c != null){
            try{
                return c.getDeclaredField(name);
            }catch(NoSuchFieldException e){
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private Object convertValue(Class<?> type, String value){
        if(type == int.class || type == Integer.class) return Integer.parseInt(value);
        if(type == float.class || type == Float.class) return Float.parseFloat(value);
        if(type == double.class || type == Double.class) return Double.parseDouble(value);
        if(type == long.class || type == Long.class) return Long.parseLong(value);
        if(type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if(type == short.class || type == Short.class) return Short.parseShort(value);
        if(type == byte.class || type == Byte.class) return Byte.parseByte(value);
        return value; // String and others
    }

    private void load(){
        if(!dataFile.exists()) return;
        try{
            String json = new String(java.nio.file.Files.readAllBytes(dataFile.toPath()), StandardCharsets.UTF_8);
            Jval root = Jval.read(json);
            if(root.isObject()){
                for(var entry : root.asObject()){
                    String ns = entry.key;
                    Jval nsVal = entry.value;
                    if(nsVal.isObject()){
                        ObjectMap<String, String> nsMap = new ObjectMap<>();
                        for(var nameEntry : nsVal.asObject()){
                            nsMap.put(nameEntry.key, nameEntry.value.toString(Jval.Jformat.plain));
                        }
                        metaStore.put(ns, nsMap);
                    }
                }
            }
            Log.info("[@] 内容注册表已加载: @ 个命名空间", MindustryYZF.name, metaStore.size);
        }catch(Exception e){
            Log.err("[@] 加载内容注册表失败", MindustryYZF.name, e);
        }
    }

    private synchronized void save(){
        try{
            Jval root = Jval.newObject();
            for(ObjectMap.Entry<String, ObjectMap<String, String>> ns : metaStore){
                Jval nsObj = Jval.newObject();
                for(ObjectMap.Entry<String, String> entry : ns.value){
                    nsObj.put(entry.key, Jval.read(entry.value));
                }
                root.put(ns.key, nsObj);
            }
            try(Writer w = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)){
                w.write(root.toString(Jval.Jformat.formatted));
            }
        }catch(Exception e){
            Log.err("[@] 保存内容注册表失败", MindustryYZF.name, e);
        }
    }
}
