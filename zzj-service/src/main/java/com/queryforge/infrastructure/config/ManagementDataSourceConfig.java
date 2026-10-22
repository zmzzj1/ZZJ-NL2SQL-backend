package com.queryforge.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 可写管理库：只保存用户与连接配置，与业务查询库完全分离。
 * 业务查询连接按用户选择的连接动态建立，不进入这个连接池。
 */
@Configuration
public class ManagementDataSourceConfig {

    @Bean
    @Primary
    public DataSource managementDataSource(ManagementProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        ManagementProperties.Pool pool = properties.hikari();
        config.setMaximumPoolSize(pool == null ? 5 : pool.maximumPoolSize());
        config.setMinimumIdle(pool == null ? 1 : pool.minimumIdle());
        config.setConnectionTimeout(pool == null ? 5000 : pool.connectionTimeout());
        config.setPoolName("management-pool");
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public JdbcTemplate managementJdbcTemplate(DataSource managementDataSource) {
        return new JdbcTemplate(managementDataSource);
    }

    @ConfigurationProperties(prefix = "app.management")
    public record ManagementProperties(String url, String username, String password, Pool hikari) {

        public record Pool(int maximumPoolSize, int minimumIdle, long connectionTimeout) {
        }
    }
}
