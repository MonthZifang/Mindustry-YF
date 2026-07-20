package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.serialization.Jval;
import mindustry.gen.Player;

public final class YZFPermissionManager{
    private final Fi file;
    private final Seq<String> defaults = new Seq<>();
    private final Seq<String> defaultRoles = new Seq<>();
    private final ObjectMap<String, Seq<String>> roles = new ObjectMap<>();
    private final ObjectMap<String, PermissionSubject> players = new ObjectMap<>();

    public YZFPermissionManager(YZFPaths paths){
        this.file = paths.permissionsFile;
    }

    public synchronized void reload(){
        defaults.clear();
        defaultRoles.clear();
        roles.clear();
        players.clear();
        if(!file.exists()) return;

        Jval root = Jval.read(YZFText.readTextSmart(file));
        if(root.has("default") && root.get("default").isArray()){
            for(Jval child : root.get("default").asArray()){
                if(child.isString()) defaults.add(child.asString());
            }
        }
        if(root.has("defaultRoles") && root.get("defaultRoles").isArray()){
            for(Jval child : root.get("defaultRoles").asArray()){
                if(child.isString()) defaultRoles.add(child.asString());
            }
        }
        if(root.has("roles") && root.get("roles").isObject()){
            for(var entry : root.get("roles").asObject()){
                Seq<String> perms = new Seq<>();
                if(entry.value.isArray()){
                    for(Jval child : entry.value.asArray()){
                        if(child.isString()) perms.add(child.asString());
                    }
                }
                roles.put(entry.key, perms);
            }
        }
        if(root.has("players") && root.get("players").isObject()){
            for(var entry : root.get("players").asObject()){
                players.put(entry.key, parseSubject(entry.value));
            }
        }
    }

    public synchronized boolean has(Player player, String permission){
        if(YZFText.blank(permission)) return true;
        if(player == null) return true;
        if(player.admin) return true;
        return has(player.uuid(), false, permission);
    }

    public synchronized boolean has(String uuid, boolean admin, String permission){
        if(YZFText.blank(permission)) return true;
        if(admin) return true;

        PermissionSubject subject = players.get(uuid);
        if(subject != null){
            if(matches(subject.permissions, permission)) return true;
            if(matchesRoles(subject.roles, permission)) return true;
        }

        if(matches(defaults, permission)) return true;
        return matchesRoles(defaultRoles, permission);
    }

    public synchronized String describe(Player player){
        if(player == null) return "<console>";
        if(player.admin) return "admin";

        PermissionSubject subject = players.get(player.uuid());
        if(subject == null){
            return defaults.isEmpty() && defaultRoles.isEmpty() ? "<none>" : "default";
        }

        Seq<String> parts = new Seq<>();
        if(!subject.roles.isEmpty()) parts.add("roles=" + subject.roles);
        if(!subject.permissions.isEmpty()) parts.add("permissions=" + subject.permissions);
        return parts.isEmpty() ? "default" : String.join(", ", parts.toArray(String.class));
    }

    public synchronized Seq<String> roles(){
        return roles.keys().toSeq();
    }

    private boolean matches(Seq<String> list, String permission){
        if(list == null) return false;
        for(String value : list){
            if(value.equals(permission)) return true;
            if(value.endsWith("*") && permission.startsWith(value.substring(0, value.length() - 1))) return true;
        }
        return false;
    }

    private boolean matchesRoles(Seq<String> roleNames, String permission){
        if(roleNames == null) return false;
        for(String roleName : roleNames){
            if(matches(roles.get(roleName), permission)) return true;
        }
        return false;
    }

    private PermissionSubject parseSubject(Jval value){
        PermissionSubject subject = new PermissionSubject();
        if(value == null) return subject;

        if(value.isArray()){
            for(Jval child : value.asArray()){
                if(child.isString()) subject.permissions.add(child.asString());
            }
            return subject;
        }

        if(value.isObject()){
            if(value.has("permissions") && value.get("permissions").isArray()){
                for(Jval child : value.get("permissions").asArray()){
                    if(child.isString()) subject.permissions.add(child.asString());
                }
            }
            if(value.has("roles") && value.get("roles").isArray()){
                for(Jval child : value.get("roles").asArray()){
                    if(child.isString()) subject.roles.add(child.asString());
                }
            }
        }
        return subject;
    }

    private static final class PermissionSubject{
        final Seq<String> permissions = new Seq<>();
        final Seq<String> roles = new Seq<>();
    }
}
