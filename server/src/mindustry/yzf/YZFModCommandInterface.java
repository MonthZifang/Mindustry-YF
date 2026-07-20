package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Player;
import rhino.Function;
import rhino.Scriptable;

/**
 * 统一的 mod 命令注册接口。
 * 将控制台命令、玩家命令、可调用命令的注册/注销统一收口。
 */
public final class YZFModCommandInterface{
    private final YZFJsRuntime runtime;
    private final YZFCommandRegistry commandRegistry;
    private final YZFAuditLog audit;
    /** 运行时注册的命令回调 (由控制台注册，模块提供实现) */
    private final java.util.concurrent.ConcurrentHashMap<String, Function> runtimeCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

    public YZFModCommandInterface(YZFJsRuntime runtime, YZFCommandRegistry commandRegistry, YZFAuditLog audit){
        this.runtime = runtime;
        this.commandRegistry = commandRegistry;
        this.audit = audit;
    }

    /** 注册控制台命令 */
    public void registerServerCommand(YZFModuleDefinition module, String name, String usage, String description, Function callback){
        runtime.registerModuleCommand(module, name, usage, description, wrapCallback(callback));
        audit.record("mod-cmd-register", module.fullId(), "server:" + name);
    }

    /** 注册玩家命令 */
    public void registerPlayerCommand(YZFModuleDefinition module, String name, String usage, String description, boolean adminOnly, String permission, Function callback){
        runtime.registerPlayerCommand(module, name, usage, description, adminOnly, permission, wrapCallback(callback));
        audit.record("mod-cmd-register", module.fullId(), "player:" + name);
    }

    /** 包装回调，null 时返回空操作 */
    private Function wrapCallback(Function callback){
        return callback;
    }

    /** 注册可调用命令 */
    public void registerCallableCommand(YZFModuleDefinition module, String name, String description, Function callback, Scriptable scope){
        commandRegistry.register(module.fullId(), name, description, callback, scope);
        audit.record("mod-cmd-register", module.fullId(), "callable:" + name);
    }

    /** 统一注销命令 (尝试所有类型) */
    public boolean unregisterCommand(String moduleId, String name){
        boolean removed = false;

        // 尝试移除控制台命令
        if(runtime.commandOwners() != null && moduleId.equals(runtime.commandOwners().get(name))){
            CommandHandler handler = MindustryYZF.context().serverControl.handler;
            handler.removeCommand(name);
            runtime.commandOwners().remove(name);
            runtime.setRuntimeCommandCallback(name, null);
            removed = true;
        }

        // 尝试移除玩家命令
        if(runtime.playerCommandOwners() != null && moduleId.equals(runtime.playerCommandOwners().get(name))){
            Vars.netServer.clientCommands.removeCommand(name);
            runtime.playerCommandOwners().remove(name);
            runtime.setRuntimePlayerCommandCallback(name, null);
            removed = true;
        }

        // 尝试移除可调用命令
        if(commandRegistry.has(name)){
            commandRegistry.unregister(moduleId, name);
            removed = true;
        }

        if(removed){
            audit.record("mod-cmd-unregister", moduleId, name);
        }
        return removed;
    }

    /** 设置运行时命令回调 (由模块提供实现) */
    public void setRuntimeCommandCallback(String commandName, Function callback){
        runtime.setRuntimeCommandCallback(commandName, callback);
    }

    /** 设置运行时玩家命令回调 */
    public void setRuntimePlayerCommandCallback(String commandName, Function callback){
        runtime.setRuntimePlayerCommandCallback(commandName, callback);
    }

    /** 列出模块注册的所有命令 (JSON) */
    public String listCommands(String moduleId){
        Jval root = Jval.newObject();

        // 控制台命令
        Jval serverCmds = Jval.newArray();
        if(runtime.commandOwners() != null){
            for(var entry : runtime.commandOwners()){
                if(moduleId.equals(entry.value)){
                    serverCmds.add(entry.key);
                }
            }
        }
        root.put("server", serverCmds);

        // 玩家命令
        Jval playerCmds = Jval.newArray();
        if(runtime.playerCommandOwners() != null){
            for(var entry : runtime.playerCommandOwners()){
                if(moduleId.equals(entry.value)){
                    playerCmds.add(entry.key);
                }
            }
        }
        root.put("player", playerCmds);

        // 可调用命令
        Jval callableCmds = Jval.newArray();
        for(var entry : commandRegistry.all()){
            if(moduleId.equals(entry.value.moduleId)){
                callableCmds.add(entry.key);
            }
        }
        root.put("callable", callableCmds);

        return root.toString(Jval.Jformat.plain);
    }

    /** 检查命令是否存在 (任意类型) */
    public boolean hasCommand(String name){
        if(runtime.commandOwners() != null && runtime.commandOwners().containsKey(name)){
            return true;
        }
        if(runtime.playerCommandOwners() != null && runtime.playerCommandOwners().containsKey(name)){
            return true;
        }
        return commandRegistry.has(name);
    }

    /** 列出所有命令概览 (JSON) */
    public String listAllCommands(){
        Jval root = Jval.newObject();

        Jval serverCmds = Jval.newArray();
        if(runtime.commandOwners() != null){
            for(var entry : runtime.commandOwners()){
                Jval obj = Jval.newObject();
                obj.put("name", entry.key);
                obj.put("module", entry.value);
                serverCmds.add(obj);
            }
        }
        root.put("server", serverCmds);

        Jval playerCmds = Jval.newArray();
        if(runtime.playerCommandOwners() != null){
            for(var entry : runtime.playerCommandOwners()){
                Jval obj = Jval.newObject();
                obj.put("name", entry.key);
                obj.put("module", entry.value);
                playerCmds.add(obj);
            }
        }
        root.put("player", playerCmds);

        Jval callableCmds = Jval.newArray();
        for(var entry : commandRegistry.all()){
            Jval obj = Jval.newObject();
            obj.put("name", entry.key);
            obj.put("module", entry.value.moduleId);
            obj.put("description", entry.value.description);
            callableCmds.add(obj);
        }
        root.put("callable", callableCmds);

        return root.toString(Jval.Jformat.plain);
    }

    /** 获取运行时引用 */
    public YZFJsRuntime runtime(){
        return runtime;
    }

    /** 获取命令注册表引用 */
    public YZFCommandRegistry commandRegistry(){
        return commandRegistry;
    }
}
