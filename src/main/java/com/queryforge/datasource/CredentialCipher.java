package com.queryforge.datasource;

import com.queryforge.identity.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 连接密码的认证加密（AES-256-GCM，随机 12 字节 nonce）。
 * 密文格式：Base64(nonce || ciphertext || tag)。密钥来自独立配置，不进数据库。
 */
@Component
public class CredentialCipher {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public CredentialCipher(SecurityProperties properties) {
        this.key = loadKey(properties.encryptionKey());
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("凭据加密失败", ex);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= NONCE_LENGTH) {
                throw new IllegalStateException("凭据密文格式不正确");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(payload, NONCE_LENGTH, payload.length - NONCE_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("凭据解密失败", ex);
        }
    }

    private static SecretKeySpec loadKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("缺少 app.security.encryption-key，无法启动连接凭据加密");
        }
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalStateException("app.security.encryption-key 必须是 32 字节的 Base64");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
