package mindustry.yzf;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class YZFHikariSqlClient implements YZFSqlClient{
    private final YZFServiceConfig config;
    private final String driverClassName;
    private final YZFDriverRegistry driverRegistry;
    private final List<HikariDataSource> pools = new ArrayList<>();
    private final AtomicInteger roundRobin = new AtomicInteger();
    private RoutingDataSource routingDataSource;

    public YZFHikariSqlClient(YZFServiceConfig config, String driverClassName, YZFDriverRegistry driverRegistry){
        this.config = config;
        this.driverClassName = driverClassName;
        this.driverRegistry = driverRegistry;
    }

    @Override
    public YZFServiceConfig config(){
        return config;
    }

    @Override
    public String summary(){
        return config.type + " " + config.clusterMode + " -> " + describeEndpoints();
    }

    @Override
    public synchronized void start(){
        stop();

        List<String> endpoints = resolveEndpoints();
        if(endpoints.isEmpty()){
            throw new IllegalStateException("SQL service is missing endpoint/nodes: " + config.id);
        }

        for(String endpoint : endpoints){
            pools.add(createPool(endpoint));
        }

        routingDataSource = new RoutingDataSource(pools);
    }

    @Override
    public synchronized void stop(){
        if(routingDataSource != null){
            routingDataSource.close();
            routingDataSource = null;
        }
        for(HikariDataSource pool : pools){
            try{
                pool.close();
            }catch(Exception ignored){
            }
        }
        pools.clear();
    }

    @Override
    public synchronized boolean healthy(){
        if(routingDataSource == null || pools.isEmpty()) return false;

        for(int i = 0; i < pools.size(); i++){
            HikariDataSource pool = pools.get(nextIndex() % pools.size());
            try(Connection connection = pool.getConnection()){
                if(connection.isValid(2)) return true;
            }catch(Exception ignored){
            }
        }
        return false;
    }

    @Override
    public synchronized String healthDetails(){
        if(routingDataSource == null) return "not started";
        return describeEndpoints();
    }

    @Override
    public synchronized DataSource dataSource(){
        return routingDataSource;
    }

    @Override
    public synchronized String jdbcUrl(){
        List<String> endpoints = resolveEndpoints();
        if(endpoints.isEmpty()){
            return buildJdbcUrl(config.endpoint == null ? "" : config.endpoint);
        }
        return buildJdbcUrl(endpoints.get(0));
    }

    private HikariDataSource createPool(String endpoint){
        String jdbcUrl = buildJdbcUrl(endpoint);
        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(createDataSource(jdbcUrl));
        if(!YZFText.blank(config.username)) hikari.setUsername(config.username);
        if(!YZFText.blank(config.password)) hikari.setPassword(config.password);
        hikari.setConnectionTimeout(config.connectTimeoutMs);
        hikari.setMaximumPoolSize(Math.max(2, config.clusterMode == YZFClusterMode.standalone ? 8 : Math.max(2, config.nodes.isEmpty() ? 2 : config.nodes.size)));
        hikari.setPoolName("YZF-" + config.id + "-" + sanitizePoolSuffix(endpoint));
        return new HikariDataSource(hikari);
    }

    private DataSource createDataSource(String jdbcUrl){
        try{
            YZFDriverHandle handle = driverRegistry.require(config);
            String effectiveDriverClass = driverClassName;
            if(!YZFText.blank(handle.definition.driverClassName)){
                effectiveDriverClass = handle.definition.driverClassName.trim();
            }
            if(!YZFText.blank(config.driverClassName)){
                effectiveDriverClass = config.driverClassName.trim();
            }
            Class<?> type = Class.forName(effectiveDriverClass, true, handle.loader);
            Driver driver = (Driver)type.getDeclaredConstructor().newInstance();
            return new YZFLoadedJdbcDataSource(driver, jdbcUrl, config.username, config.password, optionProperties());
        }catch(Exception e){
            throw new IllegalStateException("Failed to load external JDBC driver for service " + config.id, e);
        }
    }

    private Properties optionProperties(){
        Properties properties = new Properties();
        for(String option : config.options){
            int index = option.indexOf('=');
            if(index > 0 && index < option.length() - 1){
                properties.setProperty(option.substring(0, index), option.substring(index + 1));
            }
        }
        return properties;
    }

    private String buildJdbcUrl(String endpoint){
        String protocol;
        if("mariadb".equalsIgnoreCase(config.type)){
            protocol = "mariadb";
        }else if("postgresql".equalsIgnoreCase(config.type) || "postgres".equalsIgnoreCase(config.type)){
            protocol = "postgresql";
        }else{
            protocol = "mysql";
        }
        String target = endpoint == null ? "" : endpoint.trim();
        if(YZFText.blank(target)){
            target = "postgresql".equals(protocol) ? "127.0.0.1:5432" : "127.0.0.1:3306";
        }

        StringBuilder url = new StringBuilder("jdbc:" + protocol + "://" + target + "/" + config.database);
        boolean first = true;
        for(String option : config.options){
            if(option == null || option.trim().isEmpty() || option.startsWith("proxy")) continue;
            url.append(first ? "?" : "&").append(option.trim());
            first = false;
        }
        return url.toString();
    }

    private List<String> resolveEndpoints(){
        List<String> endpoints = new ArrayList<>();

        if(config.clusterMode == YZFClusterMode.standalone){
            if(!YZFText.blank(config.endpoint)){
                endpoints.add(config.endpoint.trim());
            }else if(!config.nodes.isEmpty()){
                endpoints.add(config.nodes.first().trim());
            }
            return endpoints;
        }

        if(!config.nodes.isEmpty()){
            for(String node : config.nodes){
                if(!YZFText.blank(node)) endpoints.add(node.trim());
            }
        }

        if(endpoints.isEmpty() && !YZFText.blank(config.endpoint)){
            endpoints.add(config.endpoint.trim());
        }

        return endpoints;
    }

    private String describeEndpoints(){
        List<String> endpoints = resolveEndpoints();
        if(endpoints.isEmpty()){
            return config.type + " " + config.clusterMode + " -> <unconfigured>";
        }
        if(endpoints.size() == 1){
            return config.type + " " + config.clusterMode + " -> " + buildJdbcUrl(endpoints.get(0));
        }
        return config.type + " " + config.clusterMode + " -> " + String.join(", ", endpoints) + " (routing)";
    }

    private int nextIndex(){
        int current = roundRobin.getAndIncrement();
        return current & Integer.MAX_VALUE;
    }

    private String sanitizePoolSuffix(String endpoint){
        if(endpoint == null) return "local";
        return endpoint.replace(':', '_').replace('/', '_').replace('\\', '_').replace(',', '_');
    }

    private final class RoutingDataSource implements DataSource{
        private final List<HikariDataSource> delegates;

        private RoutingDataSource(List<HikariDataSource> delegates){
            this.delegates = delegates;
        }

        @Override
        public Connection getConnection() throws SQLException{
            return select().getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException{
            return select().getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException{
            return first().getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException{
            for(HikariDataSource delegate : delegates){
                delegate.setLogWriter(out);
            }
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException{
            for(HikariDataSource delegate : delegates){
                delegate.setLoginTimeout(seconds);
            }
        }

        @Override
        public int getLoginTimeout() throws SQLException{
            return first().getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException{
            return first().getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException{
            if(iface.isInstance(this)){
                return iface.cast(this);
            }
            return first().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException{
            return iface.isInstance(this) || first().isWrapperFor(iface);
        }

        private HikariDataSource first(){
            return delegates.get(0);
        }

        private HikariDataSource select() throws SQLException{
            if(delegates.isEmpty()){
                throw new SQLException("No SQL pools available");
            }
            if(delegates.size() == 1){
                return delegates.get(0);
            }

            int start = nextIndex();
            SQLException last = null;
            for(int i = 0; i < delegates.size(); i++){
                HikariDataSource candidate = delegates.get((start + i) % delegates.size());
                try(Connection connection = candidate.getConnection()){
                    if(connection.isValid(2)){
                        return candidate;
                    }
                }catch(SQLException e){
                    last = e;
                }
            }
            if(last != null) throw last;
            throw new SQLException("No healthy SQL endpoint available");
        }

        private void close(){
            for(HikariDataSource delegate : delegates){
                try{
                    delegate.close();
                }catch(Exception ignored){
                }
            }
        }
    }
}
