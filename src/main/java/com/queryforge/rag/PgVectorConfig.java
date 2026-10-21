package com.queryforge.rag;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P12 S3：PostgreSQL + pgvector 独立数据源。
 * 与 MySQL 管理库（{@code @Primary}）分离，只存向量检索投影；不误连管理库或用户业务库。
 */
@Configuration
public class PgVectorConfig {

    @Bean
    public DataSource pgVectorDataSource(PgVectorProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(props.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setPoolName("pgvector-pool");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }

    @ConfigurationProperties(prefix = "app.pg")
    public record PgVectorProperties(String url, String username, String password, int maximumPoolSize) {

        public PgVectorProperties {
            if (url == null || url.isBlank()) {
                url = "jdbc:postgresql://127.0.0.1:5432/rageng";
            }
            if (username == null || username.isBlank()) {
                username = "postgres";
            }
            if (maximumPoolSize <= 0) {
                maximumPoolSize = 3;
            }
        }
    }
}
