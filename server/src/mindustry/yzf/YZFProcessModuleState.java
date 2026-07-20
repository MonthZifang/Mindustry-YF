package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;

public final class YZFProcessModuleState{
    public final YZFModuleDefinition definition;
    public final String runtime;
    public final Process process;
    public final YZFProtocolHost protocol;
    public final Thread stdoutThread;
    public final Thread stderrThread;
    public final Thread protocolReaderThread;
    public final Seq<String> serverCommands = new Seq<>();
    public final Seq<YZFPlayerCommandBinding> playerCommands = new Seq<>();
    public final Seq<YZFEventBinding> eventBindings = new Seq<>();
    public final Seq<YZFTaskBinding> taskBindings = new Seq<>();
    public final ObjectMap<String, String> subscriptions = new ObjectMap<>();

    public YZFProcessModuleState(YZFModuleDefinition definition, String runtime, Process process, YZFProtocolHost protocol, Thread stdoutThread, Thread stderrThread, Thread protocolReaderThread){
        this.definition = definition;
        this.runtime = runtime;
        this.process = process;
        this.protocol = protocol;
        this.stdoutThread = stdoutThread;
        this.stderrThread = stderrThread;
        this.protocolReaderThread = protocolReaderThread;
    }
}
