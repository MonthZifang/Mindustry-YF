package mindustry.core;

import mindustry.game.Team;

import java.util.concurrent.ConcurrentHashMap;

/** Runtime performance switches shared by the headless server and core simulation. */
public final class PerformanceSettings{
    /** Zero means unlimited. */
    public static volatile int fireLimit;
    public static volatile boolean fireLimitDisablesRules;
    public static volatile float fireDamageIntervalMultiplier = 1f;
    public static volatile int unitLimit;
    public static volatile int enemyUnitLimit;
    private static final ConcurrentHashMap<Integer, Integer> teamUnitLimits = new ConcurrentHashMap<>();

    private PerformanceSettings(){ }

    public static void reset(){
        fireLimit = 0;
        fireLimitDisablesRules = true;
        fireDamageIntervalMultiplier = 1f;
        unitLimit = 0;
        enemyUnitLimit = 0;
        teamUnitLimits.clear();
    }

    /** Returns the effective per-team limit. Zero means unlimited. */
    public static int unitLimit(Team team){
        if(team == null) return 0;
        Integer specific = teamUnitLimits.get(team.id);
        return specific == null ? unitLimit : specific;
    }

    /** Sets a team override. A non-positive value restores the default (unlimited by default). */
    public static void setTeamUnitLimit(int teamId, int limit){
        if(limit <= 0) teamUnitLimits.remove(teamId);
        else teamUnitLimits.put(teamId, limit);
    }
}
