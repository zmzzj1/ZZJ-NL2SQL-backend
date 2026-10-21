package com.queryforge.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.datasource.dto.ConnectionRequest;
import com.queryforge.datasource.dto.ConnectionResponse;
import com.queryforge.datasource.dto.ConnectionTestResponse;
import com.queryforge.datasource.dto.SchemaResponse;
import com.queryforge.datasource.model.ConnectionConfig;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.shared.error.TargetConnectionException;
import com.queryforge.datasource.repository.ConnectionRepository;
import com.queryforge.datasource.repository.TargetConnectionFactory;
import com.queryforge.datasource.repository.TargetSchemaRepository;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("连接配置服务")
class ConnectionServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 5L;

    @Mock
    private ConnectionRepository repository;

    @Mock
    private CredentialCipher cipher;

    @Mock
    private ConnectionTargetPolicy policy;

    @Mock
    private TargetConnectionFactory connectionFactory;

    @Mock
    private TargetSchemaRepository schemaRepository;

    /** 真实 Caffeine 缓存：直接验证命中/失效语义，不用行为桩冒充缓存。 */
    private ConnectionSchemaCache schemaCache;

    private ConnectionService service;

    @BeforeEach
    void setUp() {
        schemaCache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 500));
        service = new ConnectionService(
                repository, cipher, policy, connectionFactory, schemaRepository, schemaCache);
    }

    @Test
    @DisplayName("创建 MySQL 连接时加密密码，响应不包含密码")
    void create_shouldEncryptPassword() {
        stubPolicy();
        when(cipher.encrypt("DemoA_reader_2026!")).thenReturn("cipher-text");
        when(repository.insert(any())).thenReturn(CONNECTION_ID);

        ConnectionResponse response = service.create(USER_ID, request("mysql", "DemoA_reader_2026!"));

        ArgumentCaptor<ConnectionConfig> captor = ArgumentCaptor.forClass(ConnectionConfig.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().passwordCiphertext()).isEqualTo("cipher-text");
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.id()).isEqualTo(CONNECTION_ID);
    }

    @Test
    @DisplayName("无密码账号（如本地 root）允许空密码创建")
    void create_shouldAllowBlankPassword() {
        stubPolicy();
        when(cipher.encrypt("")).thenReturn("cipher-of-empty");
        when(repository.insert(any())).thenReturn(CONNECTION_ID);

        ConnectionResponse response = service.create(USER_ID, request("mysql", ""));

        ArgumentCaptor<ConnectionConfig> captor = ArgumentCaptor.forClass(ConnectionConfig.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().passwordCiphertext()).isEqualTo("cipher-of-empty");
        assertThat(response.hasPassword()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL 明确拒绝，不伪造支持")
    void create_shouldRejectUnsupportedEngine() {
        assertThatThrownBy(() -> service.create(USER_ID, request("postgresql", "secret")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("修改时密码留空沿用原密文")
    void update_shouldKeepExistingCiphertext_whenPasswordBlank() {
        ConnectionConfig existing = config("old-cipher");
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(existing));
        stubPolicy();
        when(repository.update(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString())).thenReturn(1);

        service.update(USER_ID, CONNECTION_ID, request("mysql", ""));

        verify(repository).update(CONNECTION_ID, USER_ID, "A 商店库", "127.0.0.1", 3306,
                "sql_demo_a", "demo_a_reader", "old-cipher");
    }

    @Test
    @DisplayName("更新成功后 Schema 缓存与目标连接池同步失效")
    void update_shouldInvalidateCaches() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        stubPolicy();
        when(cipher.encrypt("new-secret")).thenReturn("new-cipher");
        when(repository.update(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString())).thenReturn(1);
        stubSchema("shop_orders");

        service.schema(USER_ID, CONNECTION_ID);
        service.update(USER_ID, CONNECTION_ID, request("mysql", "new-secret"));

        assertThat(schemaCache.estimatedSize()).isZero();
        verify(connectionFactory).evict(CONNECTION_ID);
        // 失效后再次读取会重新访问目标库，不沿用旧 Schema。
        service.schema(USER_ID, CONNECTION_ID);
        verify(schemaRepository, times(2)).read(any());
    }

    @Test
    @DisplayName("删除成功后缓存与连接池同步失效")
    void delete_shouldInvalidateCaches() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        when(cipher.decrypt("cipher")).thenReturn("secret");
        when(repository.delete(CONNECTION_ID, USER_ID)).thenReturn(1);
        stubSchema("shop_orders");

        service.schema(USER_ID, CONNECTION_ID);
        service.delete(USER_ID, CONNECTION_ID);

        assertThat(schemaCache.estimatedSize()).isZero();
        verify(connectionFactory).evict(CONNECTION_ID);
    }

    @Test
    @DisplayName("非本人连接统一返回 404，避免枚举他人资源")
    void target_shouldRejectForeignConnection() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.target(USER_ID, CONNECTION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND);
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("测试连接失败时返回 success=false，不泄露异常细节")
    void test_shouldReportFailureSafely() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        when(cipher.decrypt("cipher")).thenReturn("secret");
        when(connectionFactory.open(any())).thenThrow(
                new TargetConnectionException("访问被拒绝 secret", new SQLException("denied")));

        ConnectionTestResponse response = service.test(USER_ID, CONNECTION_ID);

        assertThat(response.success()).isFalse();
        assertThat(response.message()).doesNotContain("secret", "denied");
    }

    @Test
    @DisplayName("Schema 读取失败转换为 CONNECTION_FAILED，且失败不写缓存")
    void schema_shouldMapFailure() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        when(cipher.decrypt("cipher")).thenReturn("secret");
        when(schemaRepository.read(any())).thenThrow(new TargetConnectionException("metadata failed", null));

        assertThatThrownBy(() -> service.schema(USER_ID, CONNECTION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_FAILED));
        assertThat(schemaCache.estimatedSize()).isZero();
    }

    @Test
    @DisplayName("同一连接重复读取 Schema 命中缓存，只访问一次目标库")
    void schema_shouldHitCacheOnRepeatRead() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        when(cipher.decrypt("cipher")).thenReturn("secret");
        stubSchema("shop_orders");

        service.schema(USER_ID, CONNECTION_ID);
        SchemaResponse second = service.schema(USER_ID, CONNECTION_ID);

        verify(schemaRepository, times(1)).read(any());
        assertThat(second.tables()).extracting(SchemaResponse.SchemaTable::name)
                .containsExactly("shop_orders");
    }

    @Test
    @DisplayName("Schema 上下文由服务端生成并包含真实表名")
    void schemaContext_shouldIncludeTables() {
        when(repository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(config("cipher")));
        when(cipher.decrypt("cipher")).thenReturn("secret");
        stubSchema("shop_orders");

        String context = service.schemaContext(USER_ID, CONNECTION_ID);

        assertThat(context).contains("sql_demo_a", "shop_orders(amount DECIMAL)");
    }

    private void stubSchema(String tableName) {
        when(schemaRepository.read(any())).thenAnswer(invocation -> new SchemaResponse("sql_demo_a", List.of(
                new SchemaResponse.SchemaTable(tableName, List.of(
                        new SchemaResponse.SchemaColumn("amount", "DECIMAL", false))))));
    }

    private void stubPolicy() {
        when(policy.normalizeHost("127.0.0.1")).thenReturn("127.0.0.1");
        when(policy.validatePort(3306)).thenReturn(3306);
        when(policy.validateIdentifier(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ConnectionRequest request(String engine, String password) {
        return new ConnectionRequest("A 商店库", engine, "127.0.0.1", 3306,
                "sql_demo_a", "demo_a_reader", password);
    }

    private static ConnectionConfig config(String ciphertext) {
        return new ConnectionConfig(CONNECTION_ID, USER_ID, "A 商店库", "mysql", "127.0.0.1",
                3306, "sql_demo_a", "demo_a_reader", ciphertext, LocalDateTime.now(), LocalDateTime.now());
    }
}
