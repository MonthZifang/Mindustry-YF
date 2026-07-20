package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.util.Log;
import rhino.Function;
import rhino.Context;
import rhino.Scriptable;
import arc.util.serialization.Jval;

import java.util.concurrent.ConcurrentHashMap;

public final class YZFCommandRegistry{
    private final ConcurrentHashMap<String, RegisteredCommand> commands = new ConcurrentHashMap<>();

    public YZFCommandRegistry(){
    }

    public void register(String moduleId, String name, String description, Function callback, Scriptable scope){
        if(name == null || name.isEmpty()){
            throw new IllegalArgumentException("command name cannot be empty");
        }
        RegisteredCommand existing = commands.get(name);
        if(existing != null && !existing.moduleId.equals(moduleId)){
            throw new IllegalStateException("command '" + name + "' is already registered by module " + existing.moduleId);
        }
        commands.put(name, new RegisteredCommand(moduleId, name, description == null ? "" : description, callback, scope));
        Log.info("[@] 注册可调用命令: @ (模块: @)", MindustryYZF.name, name, moduleId);
    }

    public void unregister(String moduleId, String name){
        RegisteredCommand cmd = commands.get(name);
        if(cmd != null && cmd.moduleId.equals(moduleId)){
            commands.remove(name);
        }
    }

    public boolean has(String name){
        return commands.containsKey(name);
    }

    public Object call(String name, Object[] args){
        RegisteredCommand cmd = commands.get(name);
        if(cmd == null){
            throw new IllegalArgumentException("找不到可调用命令: " + name);
        }
        Context ctx = Context.enter();
        try{
            return cmd.callback.call(ctx, cmd.scope, cmd.scope, args != null ? args : new Object[0]);
        }finally{
            Context.exit();
        }
    }

    public void clearModule(String moduleId){
        commands.entrySet().removeIf(e -> e.getValue().moduleId.equals(moduleId));
    }

    public String listAsJson(){
        Jval array = Jval.newArray();
        for(RegisteredCommand cmd : commands.values()){
            Jval obj = Jval.newObject();
            obj.put("name", cmd.name);
            obj.put("description", cmd.description);
            obj.put("module", cmd.moduleId);
            array.add(obj);
        }
        return array.toString(Jval.Jformat.plain);
    }

    public String listModuleAsJson(String moduleId){
        Jval array = Jval.newArray();
        for(RegisteredCommand cmd : commands.values()){
            if(cmd.moduleId.equals(moduleId)){
                Jval obj = Jval.newObject();
                obj.put("name", cmd.name);
                obj.put("description", cmd.description);
                array.add(obj);
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    public ObjectMap<String, RegisteredCommand> all(){
        ObjectMap<String, RegisteredCommand> result = new ObjectMap<>();
        for(var e : commands.entrySet()){
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    public static final class RegisteredCommand{
        public final String moduleId;
        public final String name;
        public final String description;
        public final Function callback;
        public final Scriptable scope;

        public RegisteredCommand(String moduleId, String name, String description, Function callback, Scriptable scope){
            this.moduleId = moduleId;
            this.name = name;
            this.description = description;
            this.callback = callback;
            this.scope = scope;
        }
    }
}

