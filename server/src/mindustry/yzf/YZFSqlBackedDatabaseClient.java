package mindustry.yzf;

import arc.struct.Seq;
import arc.util.serialization.Jval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public final class YZFSqlBackedDatabaseClient implements YZFDatabaseClient{
    private static final String tableName = "yzf_kv_store";

    private final YZFDatabaseDefinition definition;
    private final YZFSqlClient sqlClient;

    public YZFSqlBackedDatabaseClient(YZFDatabaseDefinition definition, YZFSqlClient sqlClient){
        this.definition = definition;
        this.sqlClient = sqlClient;
    }

    @Override
    public YZFDatabaseDefinition definition(){
        return definition;
    }

    @Override
    public String summary(){
        return "Native SQL -> " + sqlClient.jdbcUrl();
    }

    @Override
    public void start() throws Exception{
        ensureSchema();
    }

    @Override
    public void stop(){
    }

    @Override
    public boolean healthy(){
        return sqlClient != null && sqlClient.healthy();
    }

    @Override
    public String healthDetails(){
        return sqlClient == null ? "unbound" : sqlClient.jdbcUrl();
    }

    @Override
    public String listCategories() throws Exception{
        ensureSchema();
        Jval array = Jval.newArray();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select distinct category from " + tableName + " order by category asc");
            ResultSet result = statement.executeQuery()){
            while(result.next()){
                array.add(result.getString(1));
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    @Override
    public String listKeys(String category) throws Exception{
        ensureSchema();
        Jval array = Jval.newArray();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select entry_key from " + tableName + " where category = ? order by entry_key asc")){
            statement.setString(1, normalizeCategory(category));
            try(ResultSet result = statement.executeQuery()){
                while(result.next()){
                    array.add(result.getString(1));
                }
            }
        }
        return array.toString(Jval.Jformat.plain);
    }

    @Override
    public String get(String category, String key) throws Exception{
        ensureSchema();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select entry_value from " + tableName + " where category = ? and entry_key = ?")){
            statement.setString(1, normalizeCategory(category));
            statement.setString(2, normalizeKey(key));
            try(ResultSet result = statement.executeQuery()){
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    @Override
    public void set(String category, String key, String valueJson) throws Exception{
        ensureSchema();
        String normalizedCategory = normalizeCategory(category);
        String normalizedKey = normalizeKey(key);
        if(valueJson == null){
            remove(normalizedCategory, normalizedKey);
            return;
        }
        String normalizedValue = normalizeJson(valueJson);
        try(Connection connection = sqlClient.dataSource().getConnection()){
            if(isSqlite()){
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into " + tableName + " (category, entry_key, entry_value) values (?, ?, ?) " +
                    "on conflict(category, entry_key) do update set entry_value = excluded.entry_value"
                )){
                    statement.setString(1, normalizedCategory);
                    statement.setString(2, normalizedKey);
                    statement.setString(3, normalizedValue);
                    statement.executeUpdate();
                }
            }else if(isPostgres()){
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into " + tableName + " (category, entry_key, entry_value) values (?, ?, ?) " +
                    "on conflict(category, entry_key) do update set entry_value = excluded.entry_value")){
                    statement.setString(1, normalizedCategory); statement.setString(2, normalizedKey); statement.setString(3, normalizedValue); statement.executeUpdate();
                }
            }else if(isSqlServer()){
                try(PreparedStatement statement = connection.prepareStatement(
                    "merge " + tableName + " as target using (select ? as category, ? as entry_key, ? as entry_value) as source " +
                    "on target.category = source.category and target.entry_key = source.entry_key " +
                    "when matched then update set entry_value = source.entry_value, updated_at = current_timestamp " +
                    "when not matched then insert (category, entry_key, entry_value) values (source.category, source.entry_key, source.entry_value);")){
                    statement.setString(1, normalizedCategory); statement.setString(2, normalizedKey); statement.setString(3, normalizedValue); statement.executeUpdate();
                }
            }else{
                try(PreparedStatement statement = connection.prepareStatement(
                    "insert into " + tableName + " (category, entry_key, entry_value) values (?, ?, ?) " +
                    "on duplicate key update entry_value = values(entry_value)"
                )){
                    statement.setString(1, normalizedCategory);
                    statement.setString(2, normalizedKey);
                    statement.setString(3, normalizedValue);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Override
    public boolean remove(String category, String key) throws Exception{
        ensureSchema();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("delete from " + tableName + " where category = ? and entry_key = ?")){
            statement.setString(1, normalizeCategory(category));
            statement.setString(2, normalizeKey(key));
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public String dumpJson() throws Exception{
        ensureSchema();
        Jval root = Jval.newObject();
        root.put("id", definition.id);
        root.put("name", definition.name);
        root.put("type", definition.type);

        Jval categories = Jval.newObject();
        Seq<String> categoryNames = readCategoryNames();
        for(String category : categoryNames){
            Jval categoryRoot = Jval.newObject();
            try(Connection connection = sqlClient.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("select entry_key, entry_value from " + tableName + " where category = ? order by entry_key asc")){
                statement.setString(1, category);
                try(ResultSet result = statement.executeQuery()){
                    while(result.next()){
                        categoryRoot.put(result.getString(1), parseValue(result.getString(2)));
                    }
                }
            }
            categories.put(category, categoryRoot);
        }
        root.put("categories", categories);
        return root.toString(Jval.Jformat.plain);
    }

    @Override
    public void importJson(String json) throws Exception{
        ensureSchema();
        if(json == null || json.trim().isEmpty()) return;

        Jval root = Jval.read(json);
        if(root == null || !root.isObject()) return;
        Jval categories = root.has("categories") ? root.get("categories") : root;
        if(categories == null || !categories.isObject()) return;
        Connection connection = null;
        try{
            connection = sqlClient.dataSource().getConnection();
            boolean auto = connection.getAutoCommit(); connection.setAutoCommit(false);
            try(Statement clear = connection.createStatement()){
                clear.executeUpdate("delete from " + tableName);
            }
            for(var categoryEntry : categories.asObject()) if(categoryEntry.value != null && categoryEntry.value.isObject())
                for(var item : categoryEntry.value.asObject()){
                    if(item.value == null) continue;
                    String category = normalizeCategory(categoryEntry.key), key = normalizeKey(item.key), value = normalizeJson(item.value.toString(Jval.Jformat.plain));
                    try(PreparedStatement statement = connection.prepareStatement("insert into " + tableName + " (category, entry_key, entry_value) values (?, ?, ?)")){
                        statement.setString(1, category); statement.setString(2, key); statement.setString(3, value); statement.executeUpdate();
                    }
                }
            connection.commit(); connection.setAutoCommit(auto);
        }catch(Exception error){
            if(connection != null) try{ connection.rollback(); }catch(Exception ignored){}
            throw error;
        }finally{
            if(connection != null) try{ connection.close(); }catch(Exception ignored){}
        }
    }

    private Seq<String> readCategoryNames() throws Exception{
        Seq<String> categories = new Seq<>();
        try(Connection connection = sqlClient.dataSource().getConnection();
            PreparedStatement statement = connection.prepareStatement("select distinct category from " + tableName + " order by category asc");
            ResultSet result = statement.executeQuery()){
            while(result.next()){
                categories.add(result.getString(1));
            }
        }
        return categories;
    }

    private void ensureSchema() throws Exception{
        try(Connection connection = sqlClient.dataSource().getConnection(); Statement statement = connection.createStatement()){
            if(isSqlite()){
                statement.executeUpdate(
                    "create table if not exists " + tableName + " (" +
                    "category text not null," +
                    "entry_key text not null," +
                    "entry_value text not null," +
                    "updated_at integer not null default (strftime('%s','now'))," +
                    "primary key (category, entry_key))"
                );
            }else{
                statement.executeUpdate(
                    "create table if not exists " + tableName + " (" +
                    "category varchar(255) not null," +
                    "entry_key varchar(255) not null," +
                    "entry_value " + (isPostgres() ? "text" : (isSqlServer() ? "nvarchar(max)" : "longtext")) + " not null," +
                    "updated_at " + (isPostgres() ? "timestamp not null default current_timestamp" : (isSqlServer() ? "datetime2 not null default current_timestamp" : "timestamp not null default current_timestamp on update current_timestamp")) + "," +
                    "primary key (category, entry_key))"
                );
            }
        }
    }

    private boolean isSqlite(){
        return definition != null && "service-sqlite".equalsIgnoreCase(definition.type);
    }

    private boolean isPostgres(){
        return definition != null && ("service-postgresql".equalsIgnoreCase(definition.type) || "postgresql".equalsIgnoreCase(definition.type));
    }

    private boolean isSqlServer(){
        return definition != null && ("service-sqlserver".equalsIgnoreCase(definition.type) || "sqlserver".equalsIgnoreCase(definition.type) || "mssql".equalsIgnoreCase(definition.type));
    }

    private String normalizeCategory(String category){
        if(category == null) return "";
        String value = category.trim().replace('\\', '/');
        while(value.startsWith("/")) value = value.substring(1);
        while(value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String normalizeKey(String key){
        return key == null ? "" : key.trim();
    }

    private String normalizeJson(String valueJson){
        String value = valueJson == null ? "" : valueJson.trim();
        if(value.isEmpty()) return "\"\"";
        try{
            return Jval.read(value).toString(Jval.Jformat.plain);
        }catch(Exception e){
            return Jval.read("\"" + escapeJson(value) + "\"").toString(Jval.Jformat.plain);
        }
    }

    private Jval parseValue(String raw){
        if(raw == null) return null;
        try{
            return Jval.read(raw);
        }catch(Exception e){
            return Jval.read("\"" + escapeJson(raw) + "\"");
        }
    }

    private String escapeJson(String value){
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
