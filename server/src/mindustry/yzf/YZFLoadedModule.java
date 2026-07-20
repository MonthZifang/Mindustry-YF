package mindustry.yzf;

import arc.struct.Seq;
import rhino.Function;
import rhino.Scriptable;

public final class YZFLoadedModule{
    public final YZFModuleDefinition definition;
    public final Seq<String> commandNames;
    public final Seq<YZFPlayerCommandBinding> playerCommandNames;
    public final Seq<YZFEventBinding> eventBindings;
    public final Seq<YZFTaskBinding> taskBindings;
    public final Function onEnable;
    public final Function onDisable;
    public final Scriptable scope;
    public final String sourceText;

    public YZFLoadedModule(YZFModuleDefinition definition, Seq<String> commandNames, Seq<YZFPlayerCommandBinding> playerCommandNames, Seq<YZFEventBinding> eventBindings, Seq<YZFTaskBinding> taskBindings, Function onEnable, Function onDisable, Scriptable scope, String sourceText){
        this.definition = definition;
        this.commandNames = commandNames;
        this.playerCommandNames = playerCommandNames;
        this.eventBindings = eventBindings;
        this.taskBindings = taskBindings;
        this.onEnable = onEnable;
        this.onDisable = onDisable;
        this.scope = scope;
        this.sourceText = sourceText;
    }
}
