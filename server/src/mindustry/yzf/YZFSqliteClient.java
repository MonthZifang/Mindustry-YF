package mindustry.yzf;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.File;

public final class YZFSqliteClient implements YZFSqlClient{
    private final YZFServiceConfig config;
    private HikariDataSource dataSource;

    public YZFSqliteClient(YZFServiceConfig config){
        this.config = config;
    }

    @Override
    public YZFServiceConfig config(){
        return config;
    }

    @Override
    public String summary(){
        return "SQLite -> " + jdbcUrl();
    }

    @Override
    public void start(){
        ensureParentDirectory();
        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl(jdbcUrl());
        hikari.setMaximumPoolSize(1);
        hikari.setPoolName("YZF-" + config.id);
        dataSource = new HikariDataSource(hikari);
    }

    @Override
    public void stop(){
        if(dataSource != null){
            dataSource.close();
            dataSource = null;
        }
    }

    @Override
    public boolean healthy(){
        return dataSource != null && !dataSource.isClosed();
    }

    @Override
    public DataSource dataSource(){
        return dataSource;
    }

    @Override
    public String jdbcUrl(){
        String file = config.databaseFile;
        if(YZFText.blank(file)){
            file = "config/yzf/config/services/" + config.id + ".sqlite.db";
        }
        return "jdbc:sqlite:" + file;
    }

    private void ensureParentDirectory(){
        String file = config.databaseFile;
        if(YZFText.blank(file)){
            file = "config/yzf/config/services/" + config.id + ".sqlite.db";
        }
        File target = new File(file);
        File parent = target.getParentFile();
        if(parent != null && !parent.exists()){
            parent.mkdirs();
        }
    }
}
