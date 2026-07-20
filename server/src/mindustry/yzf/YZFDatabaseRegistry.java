package mindustry.yzf;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public final class YZFDatabaseRegistry{
    private final YZFPaths paths;
    private final Fi registryFile;
    private final Fi databasesDir;
    private final Fi localConfigFile;
    private final ObjectMap<String, YZFDatabaseDefinition> definitions = new ObjectMap<>();
    private final ObjectMap<String, YZFDatabaseClient> clients = new ObjectMap<>();
    private YZFServiceRegistry serviceRegistry;

    public YZFDatabaseRegistry(YZFPaths paths){
        this.paths = paths;
        this.registryFile = paths.databaseRegistryFile;
        this.databasesDir = paths.databasesDir;
        this.localConfigFile = paths.localDatabaseConfigFile;
        reload();
    }

    public synchronized void reload(){
        stopAll();
        definitions.clear();
        clients.clear();
        loadDefinitions();
        ensureDefaultLocal();
        syncServiceBackedDefinitions();
        saveDefinitions();
        startAll();
    }

    public synchronized void attachServiceRegistry(YZFServiceRegistry serviceRegistry){
        this.serviceRegistry = serviceRegistry;
        syncServiceBackedDefinitions();
        saveDefinitions();
        restartClients();
    }

    public synchronized String listJson(){
        Jval array = Jval.newArray();
        for(YZFDatabaseDefinition definition : definitions.values()){
            array.add(definitionToJson(definition));
        }
        return array.toString(Jval.Jformat.plain);
    }

    public synchronized String infoJson(String id){
        YZFDatabaseDefinition definition = definitions.get(id);
        return definition == null ? null : definitionToJson(definition).toString(Jval.Jformat.plain);
    }

    public synchronized boolean has(String id){
        return definitions.containsKey(id);
    }

    public synchronized boolean addLocal(String id, String name){
        if(YZFText.blank(id) || has(id)) return false;
        YZFDatabaseDefinition def = new YZFDatabaseDefinition();
        def.id = id.trim();
        def.name = YZFText.blank(name) ? def.id : name.trim();
        def.type = "local";
        def.sourcePath = paths.relative(databasesDir.child(def.id));
        definitions.put(def.id, def);
        ensureClient(def);
        saveDefinitions();
        return true;
    }

    public synchronized boolean addRemote(String id, String name, String endpoint, String serviceId, boolean readOnly){
        if(YZFText.blank(id) || has(id)) return false;
        YZFDatabaseDefinition def = new YZFDatabaseDefinition();
        def.id = id.trim();
        def.name = YZFText.blank(name) ? def.id : name.trim();
        def.type = "remote";
        def.endpoint = endpoint == null ? "" : endpoint.trim();
        def.serviceId = serviceId == null ? "" : serviceId.trim();
        def.readOnly = readOnly;
        definitions.put(def.id, def);
        saveDefinitions();
        return true;
    }

    public synchronized boolean remove(String id){
        if("local".equalsIgnoreCase(id)) return false;
        YZFDatabaseDefinition definition = definitions.get(id);
        if(definition != null && definition.managedByService) return false;
        YZFDatabaseDefinition removed = definitions.remove(id);
        YZFDatabaseClient client = clients.remove(id);
        if(client != null) client.stop();
        if(removed != null){
            if("local".equalsIgnoreCase(removed.type)){
                Fi dir = databasesDir.child(removed.id);
                if(dir.exists()) dir.deleteDirectory();
            }
            saveDefinitions();
            return true;
        }
        return false;
    }

    public synchronized String categoriesJson(String id) throws Exception{
        YZFDatabaseClient client = requireClient(id);
        return client.listCategories();
    }

    public synchronized String keysJson(String id, String category) throws Exception{
        YZFDatabaseClient client = requireClient(id);
        return client.listKeys(category);
    }

    public synchronized String get(String id, String category, String key) throws Exception{
        YZFDatabaseClient client = requireClient(id);
        return client.get(category, key);
    }

    public synchronized void set(String id, String category, String key, String valueJson) throws Exception{
        YZFDatabaseDefinition definition = definitions.get(id);
        if(definition != null && definition.readOnly){
            throw new IllegalStateException("database is read-only: " + id);
        }
        YZFDatabaseClient client = requireClient(id);
        client.set(category, key, valueJson);
    }

    public synchronized boolean removeEntry(String id, String category, String key) throws Exception{
        YZFDatabaseDefinition definition = definitions.get(id);
        if(definition != null && definition.readOnly){
            throw new IllegalStateException("database is read-only: " + id);
        }
        YZFDatabaseClient client = requireClient(id);
        return client.remove(category, key);
    }

    public synchronized String dumpJson(String id) throws Exception{
        return requireClient(id).dumpJson();
    }

    public synchronized void importJson(String id, String json) throws Exception{
        YZFDatabaseDefinition definition = definitions.get(id);
        if(definition != null && definition.readOnly){
            throw new IllegalStateException("database is read-only: " + id);
        }
        requireClient(id).importJson(json);
    }

    public synchronized String defaultId(){
        return "local";
    }

    public synchronized int count(){
        return definitions.size;
    }

    public synchronized void shutdown(){
        stopAll();
        clients.clear();
    }

    private void ensureDefaultLocal(){
        YZFDatabaseDefinition def = loadLocalDefinition();
        if(def == null || !def.enabled){
            definitions.remove("local");
            YZFDatabaseClient client = clients.remove("local");
            if(client != null) client.stop();
            return;
        }
        def.sourcePath = paths.relative(databasesDir.child("local"));
        definitions.put(def.id, def);
    }

    private void loadDefinitions(){
        if(!registryFile.exists()) return;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(registryFile));
            Jval items = root == null ? null : root.get("databases");
            if(items != null && items.isArray()){
                for(var entry : items.asArray()){
                    if(entry == null || !entry.isObject()) continue;
                    YZFDatabaseDefinition def = jsonToDefinition(entry);
                    if(def.id != null){
                        definitions.put(def.id, def);
                    }
                }
            }
        }catch(Exception e){
            Log.err("[@] Failed to load database registry", MindustryYZF.name, e);
        }
    }

    private void saveDefinitions(){
        try{
            Fi parent = registryFile.parent();
            if(parent != null) parent.mkdirs();
            Jval root = Jval.newObject();
            Jval array = Jval.newArray();
            for(YZFDatabaseDefinition definition : definitions.values()){
                array.add(definitionToJson(definition));
            }
            root.put("databases", array);
            try(Writer writer = new OutputStreamWriter(new FileOutputStream(registryFile.file()), StandardCharsets.UTF_8)){
                writer.write(root.toString(Jval.Jformat.formatted));
            }
        }catch(Exception e){
            Log.err("[@] Failed to save database registry", MindustryYZF.name, e);
        }
    }

    private void startAll(){
        for(YZFDatabaseDefinition def : definitions.values()){
            ensureClient(def);
        }
    }

    private void restartClients(){
        stopAll();
        clients.clear();
        startAll();
    }

    private void stopAll(){
        for(YZFDatabaseClient client : clients.values()){
            try{
                client.stop();
            }catch(Exception ignored){
            }
        }
    }

    private YZFDatabaseClient ensureClient(YZFDatabaseDefinition def){
        YZFDatabaseClient client = clients.get(def.id);
        if(client != null) return client;
        client = createClient(def);
        clients.put(def.id, client);
        try{
            client.start();
        }catch(Exception e){
            Log.err("[@] Failed to start database @", MindustryYZF.name, def.id, e);
        }
        return client;
    }

    private YZFDatabaseClient requireClient(String id){
        YZFDatabaseDefinition def = definitions.get(id);
        if(def == null){
            throw new IllegalStateException("Unknown database: " + id);
        }
        return ensureClient(def);
    }

    private YZFDatabaseClient createClient(YZFDatabaseDefinition def){
        if("remote".equalsIgnoreCase(def.type)){
            return new YZFRemoteJsonDatabaseClient(def);
        }
        if(def.managedByService){
            YZFSqlClient sqlClient = resolveSqlClient(def.serviceId);
            if(sqlClient == null){
                throw new IllegalStateException("Unknown SQL service for database: " + def.serviceId);
            }
            def.jdbcUrl = sqlClient.jdbcUrl();
            return new YZFSqlBackedDatabaseClient(def, sqlClient);
        }
        Fi dir = databasesDir.child(def.id);
        def.sourcePath = paths.relative(dir);
        return new YZFLocalJsonDatabaseClient(def, dir);
    }

    private Jval definitionToJson(YZFDatabaseDefinition def){
        Jval root = Jval.newObject();
        root.put("id", def.id);
        root.put("name", def.name);
        root.put("type", def.type);
        root.put("enabled", def.enabled);
        root.put("readOnly", def.readOnly);
        root.put("description", def.description);
        root.put("endpoint", def.endpoint);
        root.put("serviceId", def.serviceId);
        root.put("basePath", def.basePath);
        root.put("sourcePath", def.sourcePath);
        root.put("jdbcUrl", def.jdbcUrl);
        root.put("driverId", def.driverId);
        root.put("managedByService", def.managedByService);
        return root;
    }

    private YZFDatabaseDefinition jsonToDefinition(Jval data){
        YZFDatabaseDefinition def = new YZFDatabaseDefinition();
        def.id = data.getString("id", null);
        def.name = data.getString("name", def.id);
        def.type = data.getString("type", "local");
        def.enabled = data.getBool("enabled", true);
        def.readOnly = data.getBool("readOnly", false);
        def.description = data.getString("description", "");
        def.endpoint = data.getString("endpoint", "");
        def.serviceId = data.getString("serviceId", "");
        def.basePath = data.getString("basePath", "");
        def.sourcePath = data.getString("sourcePath", "");
        def.jdbcUrl = data.getString("jdbcUrl", "");
        def.driverId = data.getString("driverId", "");
        def.managedByService = data.getBool("managedByService", false);
        return def;
    }

    private void syncServiceBackedDefinitions(){
        Seq<String> toRemove = new Seq<>();
        for(YZFDatabaseDefinition definition : definitions.values()){
            if(definition.managedByService) toRemove.add(definition.id);
        }
        for(String id : toRemove){
            definitions.remove(id);
            YZFDatabaseClient client = clients.remove(id);
            if(client != null) client.stop();
        }

        if(serviceRegistry == null) return;

        for(YZFServiceClient service : serviceRegistry.all()){
            if(!(service instanceof YZFSqlClient sqlClient)) continue;
            YZFServiceType type = service.config().typeEnum();
            if(type != YZFServiceType.mysql && type != YZFServiceType.mariadb && type != YZFServiceType.sqlite && type != YZFServiceType.postgresql) continue;

            YZFDatabaseDefinition definition = new YZFDatabaseDefinition();
            definition.id = "service-" + service.config().id;
            definition.name = service.config().id + " Native Database";
            definition.type = "service-" + service.config().type;
            definition.enabled = service.config().enabled;
            definition.readOnly = false;
            definition.description = "Native SQL-backed database mapped from service " + service.config().id;
            definition.serviceId = service.config().id;
            definition.sourcePath = service.config().sourcePath;
            definition.jdbcUrl = sqlClient.jdbcUrl();
            definition.driverId = service.config().driverId;
            definition.managedByService = true;
            definitions.put(definition.id, definition);
        }
    }

    private YZFSqlClient resolveSqlClient(String serviceId){
        if(serviceRegistry == null || YZFText.blank(serviceId)) return null;
        return serviceRegistry.getAs(serviceId, YZFSqlClient.class);
    }

    private YZFDatabaseDefinition loadLocalDefinition(){
        YZFDatabaseDefinition def = new YZFDatabaseDefinition();
        def.id = "local";
        def.name = "Local JSON Database";
        def.type = "local";
        def.enabled = true;
        def.description = "Built-in local JSON database controlled by config/databases/local-json.hjson";
        def.sourcePath = paths.relative(databasesDir.child("local"));

        if(localConfigFile == null || !localConfigFile.exists()) return def;
        try{
            Jval root = Jval.read(YZFText.readTextSmart(localConfigFile));
            if(root != null && root.isObject()){
                def.id = root.getString("id", def.id);
                def.name = root.getString("name", def.name);
                def.type = root.getString("type", def.type);
                def.enabled = root.getBool("enabled", def.enabled);
                def.readOnly = root.getBool("readOnly", def.readOnly);
                def.description = root.getString("description", def.description);
            }
        }catch(Exception e){
            Log.err("[@] Failed to load local database config", MindustryYZF.name, e);
        }
        return def;
    }
}
