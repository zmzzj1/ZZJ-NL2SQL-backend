package com.queryforge.platform.repository;

import com.queryforge.platform.config.ConnectionPolicyProperties;
import com.queryforge.platform.entity.ConnectionTarget;
import com.queryforge.platform.exception.TargetConnectionException;
import com.queryforge.platform.service.ConnectionTargetPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.stereotype.Component;

/**
 * 按授权连接动态建立 JDBC 连接；URL 只由已校验的 host/port/database 拼装，
 * 不接受客户端传入的任意 JDBC 参数。
 */
@Component
public class TargetConnectionFactory {

    private final ConnectionPolicyProperties properties;
    private final ConnectionTargetPolicy policy;

    public TargetConnectionFactory(ConnectionPolicyProperties properties, ConnectionTargetPolicy policy) {
        this.properties = properties;
        this.policy = policy;
    }

    public Connection open(ConnectionTarget target) {
        String host = policy.normalizeHost(target.host());
        int port = policy.validatePort(target.port());
        String database = policy.validateIdentifier(target.database(), "数据库名");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowMultiQueries=false"
                + "&connectTimeout=" + properties.connectTimeoutMs()
                + "&socketTimeout=" + properties.socketTimeoutMs();
        try {
            Connection connection = DriverManager.getConnection(url, target.username(), target.password());
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION TRANSACTION READ ONLY");
            }
            return connection;
        } catch (SQLException ex) {
            throw new TargetConnectionException("无法连接目标数据库", ex);
        }
    }
}
