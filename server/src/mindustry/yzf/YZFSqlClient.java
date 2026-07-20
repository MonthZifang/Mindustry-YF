package mindustry.yzf;

import javax.sql.DataSource;

public interface YZFSqlClient extends YZFServiceClient{
    DataSource dataSource();

    String jdbcUrl();
}
