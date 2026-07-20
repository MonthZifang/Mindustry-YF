package mindustry.yzf;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public final class YZFLoadedJdbcDataSource implements DataSource{
    private final Driver driver;
    private final String jdbcUrl;
    private final Properties baseProperties = new Properties();
    private PrintWriter logWriter;
    private int loginTimeout;

    public YZFLoadedJdbcDataSource(Driver driver, String jdbcUrl, String username, String password, Properties properties){
        this.driver = driver;
        this.jdbcUrl = jdbcUrl;
        if(properties != null){
            this.baseProperties.putAll(properties);
        }
        if(!YZFText.blank(username)){
            this.baseProperties.setProperty("user", username);
        }
        if(!YZFText.blank(password)){
            this.baseProperties.setProperty("password", password);
        }
    }

    @Override
    public Connection getConnection() throws SQLException{
        Connection connection = driver.connect(jdbcUrl, copyProperties());
        if(connection == null){
            throw new SQLException("Driver did not accept JDBC URL: " + jdbcUrl);
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException{
        Properties properties = copyProperties();
        if(username != null) properties.setProperty("user", username);
        if(password != null) properties.setProperty("password", password);
        Connection connection = driver.connect(jdbcUrl, properties);
        if(connection == null){
            throw new SQLException("Driver did not accept JDBC URL: " + jdbcUrl);
        }
        return connection;
    }

    @Override
    public PrintWriter getLogWriter(){
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out){
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds){
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout(){
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException{
        return driver.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException{
        if(iface.isInstance(this)){
            return iface.cast(this);
        }
        if(iface.isInstance(driver)){
            return iface.cast(driver);
        }
        throw new SQLException("Unsupported unwrap: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface){
        return iface.isInstance(this) || iface.isInstance(driver);
    }

    private Properties copyProperties(){
        Properties properties = new Properties();
        properties.putAll(baseProperties);
        return properties;
    }
}
