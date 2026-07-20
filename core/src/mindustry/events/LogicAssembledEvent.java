package mindustry.events;

import mindustry.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

/**
 * Fired after LAssembler finishes assemble and is about to load into LExecutor.
 * Allows mods to modify LAssembler, try parse InvalidStatement again.
 * Not fired when LogicBlock loads empty code.
 */
public class LogicAssembledEvent{
    public LogicBuild build;
    public LAssembler assembler;

    public LogicAssembledEvent(LogicBuild build, LAssembler assembler){
        this.build = build;
        this.assembler = assembler;
    }
}
