package com.queryforge.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.identity.SecurityProperties;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("连接凭据加密")
class CredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("加密后可解密，且相同明文每次密文不同")
    void encrypt_shouldRoundTripWithRandomNonce() {
        CredentialCipher cipher = new CredentialCipher(new SecurityProperties(KEY));

        String first = cipher.encrypt("DemoA_reader_2026!");
        String second = cipher.encrypt("DemoA_reader_2026!");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("DemoA_reader_2026!");
        assertThat(cipher.decrypt(second)).isEqualTo("DemoA_reader_2026!");
    }

    @Test
    @DisplayName("密文被篡改时解密失败")
    void decrypt_shouldFail_whenCiphertextTampered() {
        CredentialCipher cipher = new CredentialCipher(new SecurityProperties(KEY));
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺少密钥时启动失败，不静默使用弱默认值")
    void constructor_shouldFail_whenKeyMissing() {
        assertThatThrownBy(() -> new CredentialCipher(new SecurityProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-key");
    }
}
