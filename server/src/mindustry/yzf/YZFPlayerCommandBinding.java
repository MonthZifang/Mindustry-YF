package mindustry.yzf;

public final class YZFPlayerCommandBinding{
    public final String name;
    public final boolean adminOnly;
    public final String permission;

    public YZFPlayerCommandBinding(String name, boolean adminOnly, String permission){
        this.name = name;
        this.adminOnly = adminOnly;
        this.permission = permission;
    }
}
