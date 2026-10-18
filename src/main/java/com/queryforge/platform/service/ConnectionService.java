package com.queryforge.platform.service;

import com.queryforge.platform.dto.ConnectionRequest;
import com.queryforge.platform.dto.ConnectionResponse;
import com.queryforge.platform.dto.ConnectionTestResponse;
import com.queryforge.platform.dto.SchemaResponse;
import com.queryforge.platform.entity.ConnectionConfig;
import com.queryforge.platform.entity.ConnectionTarget;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.exception.TargetConnectionException;
import com.queryforge.platform.repository.ConnectionRepository;
import com.queryforge.platform.repository.TargetConnectionFactory;
import com.queryforge.platform.repository.TargetSchemaRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 连接配置的业务边界：归属校验、目标白名单、凭据加解密、真实连通性测试与 Schema 读取。
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int MAX_SCHEMA_CONTEXT_CHARS = 6000;
    private static final String SUPPORTED_ENGINE = "mysql";

    private final ConnectionRepository repository;
    private final CredentialCipher cipher;
    private final ConnectionTargetPolicy policy;
    private final TargetConnectionFactory connectionFactory;
    private final TargetSchemaRepository schemaRepository;

    public ConnectionService(
            ConnectionRepository repository,
            CredentialCipher cipher,
            ConnectionTargetPolicy policy,
            TargetConnectionFactory connectionFactory,
            TargetSchemaRepository schemaRepository) {
        this.repository = repository;
        this.cipher = cipher;
        this.policy = policy;
        this.connectionFactory = connectionFactory;
        this.schemaRepository = schemaRepository;
    }

    public List<ConnectionResponse> list(Long userId) {
        return repository.findByUser(userId).stream().map(ConnectionService::toResponse).toList();
    }

    public ConnectionResponse create(Long userId, ConnectionRequest request) {
        ValidatedConnection validated = validate(request, true);
        Long id = repository.insert(new ConnectionConfig(
                null,
                userId,
                validated.name(),
                SUPPORTED_ENGINE,
                validated.host(),
                validated.port(),
                validated.database(),
                validated.username(),
                cipher.encrypt(validated.password()),
                null,
                null));
        return new ConnectionResponse(id, validated.name(), SUPPORTED_ENGINE, validated.host(),
                validated.port(), validated.database(), validated.username(), true);
    }

    public ConnectionResponse update(Long userId, Long id, ConnectionRequest request) {
        ConnectionConfig existing = requireOwned(id, userId);
        ValidatedConnection validated = validate(request, false);
        String ciphertext = (validated.password() == null || validated.password().isBlank())
                ? existing.passwordCiphertext()
                : cipher.encrypt(validated.password());
        int updated = repository.update(id, userId, validated.name(), validated.host(), validated.port(),
                validated.database(), validated.username(), ciphertext);
        if (updated == 0) {
            throw notFound();
        }
        return new ConnectionResponse(id, validated.name(), SUPPORTED_ENGINE, validated.host(),
                validated.port(), validated.database(), validated.username(), !ciphertext.isBlank());
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw notFound();
        }
    }

    public ConnectionTestResponse test(Long userId, Long id) {
        ConnectionTarget target = target(userId, id);
        long startedAt = System.nanoTime();
        try (Connection connection = connectionFactory.open(target);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new ConnectionTestResponse(true, "连接成功", latencyMs);
        } catch (TargetConnectionException | SQLException ex) {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("connection_test_failed id={} type={}", id, ex.getClass().getSimpleName());
            return new ConnectionTestResponse(false, "连接失败，请检查主机、端口、账号与密码", latencyMs);
        }
    }

    public SchemaResponse schema(Long userId, Long id) {
        ConnectionTarget target = target(userId, id);
        try {
            return schemaRepository.read(target);
        } catch (TargetConnectionException ex) {
            log.warn("schema_read_failed connectionId={} type={}", id, ex.getClass().getSimpleName());
            throw new ApiException(
                    ErrorCode.CONNECTION_FAILED, HttpStatus.BAD_GATEWAY, "无法读取该数据源的表结构");
        }
    }

    public ConnectionTarget target(Long userId, Long id) {
        ConnectionConfig config = requireOwned(id, userId);
        return new ConnectionTarget(
                config.id(),
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                cipher.decrypt(config.passwordCiphertext()));
    }

    /**
     * 由服务端根据连接读取 Schema 并裁剪为模型上下文；客户端不能覆盖。
     */
    public String schemaContext(Long userId, Long id) {
        SchemaResponse schema = schema(userId, id);
        StringBuilder builder = new StringBuilder("数据库：").append(schema.database()).append('\n');
        if (schema.tables().isEmpty()) {
            builder.append("（当前连接没有可读取的表）");
            return builder.toString();
        }
        for (SchemaResponse.SchemaTable table : schema.tables()) {
            String line = table.name() + "(" + table.columns().stream()
                    .map(column -> column.name() + " " + column.type())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("") + ")\n";
            if (builder.length() + line.length() > MAX_SCHEMA_CONTEXT_CHARS) {
                builder.append("（Schema 已截断）");
                break;
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private ConnectionConfig requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(ConnectionService::notFound);
    }

    private ValidatedConnection validate(ConnectionRequest request, boolean passwordRequired) {
        String engine = request.engine() == null ? "" : request.engine().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ENGINE.equals(engine)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "当前仅支持 MySQL 连接");
        }
        String password = request.password();
        if (passwordRequired && (password == null || password.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "连接密码不能为空");
        }
        return new ValidatedConnection(
                request.name().trim(),
                policy.normalizeHost(request.host()),
                policy.validatePort(request.port()),
                policy.validateIdentifier(request.database(), "数据库名"),
                policy.validateIdentifier(request.username(), "用户名"),
                password);
    }

    private static ConnectionResponse toResponse(ConnectionConfig config) {
        return new ConnectionResponse(
                config.id(),
                config.name(),
                config.engine(),
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                config.passwordCiphertext() != null && !config.passwordCiphertext().isBlank());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONNECTION_NOT_FOUND, HttpStatus.NOT_FOUND, "连接不存在或无权访问");
    }

    private record ValidatedConnection(
            String name, String host, int port, String database, String username, String password) {
    }
}
