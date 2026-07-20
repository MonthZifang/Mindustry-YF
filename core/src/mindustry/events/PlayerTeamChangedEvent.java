package mindustry.events;

import mindustry.game.*;
import mindustry.gen.*;

/** Called after the team of a player changed. */
public class PlayerTeamChangedEvent{
    public final Team previous;
    public final Player player;

    public PlayerTeamChangedEvent(Team previous, Player player){
        this.previous = previous;
        this.player = player;
    }
}
