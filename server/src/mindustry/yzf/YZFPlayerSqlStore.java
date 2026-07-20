package mindustry.yzf;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

public final class YZFPlayerSqlStore{
    public static final int defaultPageSize = 15;

    private final YZFSqlClient sqlClient;
    private final boolean sqlite;

    public YZFPlayerSqlStore(YZFSqlClient sqlClient){
        this.sqlClient = sqlClient;
        this.sqlite = sqlClient != null && sqlClient.config() != null && "sqlite".equalsIgnoreCase(sqlClient.config().type);
    }

    public void ensureSchema(){
        if(sqlClient == null) return;
        try(Connection connection = sqlClient.dataSource().getConnection(); Statement statement = connection.createStatement()){
            if(sqlite){
                statement.executeUpdate("create table if not exists yzf_comid_registry (uuid text primary key, comid integer not null unique)");
                statement.executeUpdate("create table if not exists yzf_player_data (comid integer not null, data_key text not null, data_value text, primary key (comid, data_key))");
                statement.executeUpdate("create table if not exists yzf_player_profiles (uuid text primary key, comid integer not null unique, first_seen_at integer not null, comid_assigned_at integer not null, last_bound_at integer not null, last_name text, last_ip text)");
            }else{
                statement.executeUpdate("create table if not exists yzf_comid_registry (uuid varchar(128) primary key, comid bigint not null unique)");
                statement.executeUpdate("create table if not exists yzf_player_data (comid bigint not null, data_key varchar(255) not null, data_value longtext null, primary key (comid, data_key))");
                statement.executeUpdate("create table if not exists yzf_player_profiles (uuid varchar(128) primary key, comid bigint not null unique, first_seen_at bigint not null, comid_assigned_at bigint not null, last_bound_at bigint not null, last_name varchar(255) null, last_ip varchar(255) null)");
            }
        }catch(Exception e){
            Log.err("[@] Failed to initialize player SQL schema", MindustryYZF.name, e);
        }
    }

    public ObjectMap<String, Long> loadComidMappings(){
        ObjectMap<String, Long> out = new ObjectMap<>();
        if(sqlClient == null) return out;
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select uuid, comid from yzf_comid_registry");
            ResultSet result = statement.executeQuery()){
            while(result.next()){
                out.put(result.getString(1), result.getLong(2));
            }
        }catch(Exception e){
            Log.err("[@] Failed to load COM ID mappings from SQL store", MindustryYZF.name, e);
        }
        return out;
    }

    public void upsertComid(String uuid, long comid){
        if(sqlClient == null) return;
        try(Connection connection = sqlClient.dataSource().getConnection()){
            if(sqlite){
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into yzf_comid_registry (uuid, comid) values (?, ?) on conflict(uuid) do update set comid = excluded.comid"
                )){
                    statement.setString(1, uuid);
                    statement.setLong(2, comid);
                    statement.executeUpdate();
                }
            }else{
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into yzf_comid_registry (uuid, comid) values (?, ?) on duplicate key update comid = values(comid)"
                )){
                    statement.setString(1, uuid);
                    statement.setLong(2, comid);
                    statement.executeUpdate();
                }catch(Exception mysqlStyle){
                    try(PreparedStatement pg = connection.prepareStatement(
                        "insert into yzf_comid_registry (uuid, comid) values (?, ?) on conflict (uuid) do update set comid = excluded.comid"
                    )){
                        pg.setString(1, uuid);
                        pg.setLong(2, comid);
                        pg.executeUpdate();
                    }
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to save COM ID mapping to SQL store", MindustryYZF.name, e);
        }
    }

    public void touchPlayerProfile(String uuid, long comid, String name, String ip, long timestamp){
        if(sqlClient == null || YZFText.blank(uuid) || comid <= 0L) return;
        String safeName = name == null ? "" : name.trim();
        String safeIp = ip == null ? "" : ip.trim();
        try(Connection connection = sqlClient.dataSource().getConnection()){
            if(sqlite){
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into yzf_player_profiles (uuid, comid, first_seen_at, comid_assigned_at, last_bound_at, last_name, last_ip) " +
                    "values (?, ?, ?, ?, ?, ?, ?) " +
                    "on conflict(uuid) do update set comid = excluded.comid, last_bound_at = excluded.last_bound_at, last_name = excluded.last_name, last_ip = excluded.last_ip"
                )){
                    statement.setString(1, uuid);
                    statement.setLong(2, comid);
                    statement.setLong(3, timestamp);
                    statement.setLong(4, timestamp);
                    statement.setLong(5, timestamp);
                    statement.setString(6, safeName);
                    statement.setString(7, safeIp);
                    statement.executeUpdate();
                }
            }else{
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into yzf_player_profiles (uuid, comid, first_seen_at, comid_assigned_at, last_bound_at, last_name, last_ip) " +
                    "values (?, ?, ?, ?, ?, ?, ?) " +
                    "on duplicate key update comid = values(comid), last_bound_at = values(last_bound_at), last_name = values(last_name), last_ip = values(last_ip)"
                )){
                    statement.setString(1, uuid);
                    statement.setLong(2, comid);
                    statement.setLong(3, timestamp);
                    statement.setLong(4, timestamp);
                    statement.setLong(5, timestamp);
                    statement.setString(6, safeName);
                    statement.setString(7, safeIp);
                    statement.executeUpdate();
                }catch(Exception mysqlStyle){
                    try(PreparedStatement statement = connection.prepareStatement(
                        "insert into yzf_player_profiles (uuid, comid, first_seen_at, comid_assigned_at, last_bound_at, last_name, last_ip) " +
                        "values (?, ?, ?, ?, ?, ?, ?) " +
                        "on conflict (uuid) do update set comid = excluded.comid, last_bound_at = excluded.last_bound_at, last_name = excluded.last_name, last_ip = excluded.last_ip"
                    )){
                        statement.setString(1, uuid);
                        statement.setLong(2, comid);
                        statement.setLong(3, timestamp);
                        statement.setLong(4, timestamp);
                        statement.setLong(5, timestamp);
                        statement.setString(6, safeName);
                        statement.setString(7, safeIp);
                        statement.executeUpdate();
                    }
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to update player profile in SQL store", MindustryYZF.name, e);
        }
    }

    public PlayerDirectoryPage listPlayerProfiles(int page, int pageSize, boolean includeUuid){
        PlayerDirectoryPage out = new PlayerDirectoryPage();
        out.page = Math.max(1, page);
        out.pageSize = Math.max(1, pageSize);
        out.includeUuid = includeUuid;
        if(sqlClient == null) return out;

        int offset = (out.page - 1) * out.pageSize;
        try(Connection connection = sqlClient.dataSource().getConnection()){
            try(PreparedStatement count = connection.prepareStatement("select count(*) from yzf_player_profiles");
                ResultSet countResult = count.executeQuery()){
                if(countResult.next()){
                    out.total = countResult.getInt(1);
                }
            }

            String sql = "select uuid, comid, first_seen_at, comid_assigned_at, last_bound_at, last_name, last_ip " +
                "from yzf_player_profiles order by comid asc limit ? offset ?";
            try(PreparedStatement statement = connection.prepareStatement(sql)){
                statement.setInt(1, out.pageSize);
                statement.setInt(2, offset);
                try(ResultSet result = statement.executeQuery()){
                    while(result.next()){
                        out.records.add(readProfile(result));
                    }
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to list player profiles from SQL store", MindustryYZF.name, e);
        }
        return out;
    }

    public PlayerProfile findPlayerProfile(String uuid){
        if(sqlClient == null || YZFText.blank(uuid)) return null;
        String normalized = uuid.trim();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "select uuid, comid, first_seen_at, comid_assigned_at, last_bound_at, last_name, last_ip from yzf_player_profiles where lower(uuid) = ?"
            )){
            statement.setString(1, normalized.toLowerCase(Locale.ROOT));
            try(ResultSet result = statement.executeQuery()){
                if(result.next()){
                    return readProfile(result);
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to query player profile from SQL store", MindustryYZF.name, e);
        }
        return null;
    }

    public ObjectMap<String, String> loadPlayerData(long comid){
        ObjectMap<String, String> out = new ObjectMap<>();
        if(sqlClient == null) return out;
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select data_key, data_value from yzf_player_data where comid = ?")){
            statement.setLong(1, comid);
            try(ResultSet result = statement.executeQuery()){
                while(result.next()){
                    out.put(result.getString(1), result.getString(2));
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to load player data from SQL store", MindustryYZF.name, e);
        }
        return out;
    }

    public void savePlayerData(long comid, ObjectMap<String, String> data){
        if(sqlClient == null) return;
        try(Connection connection = sqlClient.dataSource().getConnection()){
            try(PreparedStatement delete = connection.prepareStatement("delete from yzf_player_data where comid = ?")){
                delete.setLong(1, comid);
                delete.executeUpdate();
            }
            if(data == null) return;
            for(ObjectMap.Entry<String, String> entry : data){
                try(PreparedStatement insert = connection.prepareStatement("insert into yzf_player_data (comid, data_key, data_value) values (?, ?, ?)")){
                    insert.setLong(1, comid);
                    insert.setString(2, entry.key);
                    insert.setString(3, entry.value);
                    insert.executeUpdate();
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to save player data to SQL store", MindustryYZF.name, e);
        }
    }

    public String exportPlayerDataJson(long comid){
        ObjectMap<String, String> data = loadPlayerData(comid);
        Jval obj = Jval.newObject();
        for(ObjectMap.Entry<String, String> entry : data){
            obj.put(entry.key, entry.value);
        }
        return obj.toString(Jval.Jformat.plain);
    }

    private PlayerProfile readProfile(ResultSet result) throws Exception{
        PlayerProfile record = new PlayerProfile();
        record.uuid = result.getString("uuid");
        record.comid = result.getLong("comid");
        record.firstSeenAt = result.getLong("first_seen_at");
        record.comidAssignedAt = result.getLong("comid_assigned_at");
        record.lastBoundAt = result.getLong("last_bound_at");
        record.lastName = result.getString("last_name");
        record.lastIp = result.getString("last_ip");
        return record;
    }

    public static final class PlayerDirectoryPage{
        public int page;
        public int pageSize;
        public int total;
        public boolean includeUuid;
        public final Seq<PlayerProfile> records = new Seq<>();

        public int totalPages(){
            return Math.max(1, (total + pageSize - 1) / pageSize);
        }
    }

    public static final class PlayerProfile{
        public String uuid;
        public long comid;
        public long firstSeenAt;
        public long comidAssignedAt;
        public long lastBoundAt;
        public String lastName;
        public String lastIp;
    }
}
