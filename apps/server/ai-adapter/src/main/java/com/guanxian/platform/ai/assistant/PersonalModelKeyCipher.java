package com.guanxian.platform.ai.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public final class PersonalModelKeyCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PersonalModelKeyCipher(@Value("${guanxian.ai.personal.encryption-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            key = null;
        } else {
            try {
                byte[] decoded = Base64.getDecoder().decode(configuredKey.strip());
                if (decoded.length != 32) throw new IllegalArgumentException();
                key = new SecretKeySpec(decoded, "AES");
                java.util.Arrays.fill(decoded, (byte) 0);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Personal model encryption requires a base64-encoded 32-byte key");
            }
        }
    }

    public boolean available() { return key != null; }

    public String encrypt(UUID userId, PersonalModelProvider provider, String plaintext) {
        requireAvailable();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] input = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, userId, provider);
            byte[] encrypted = cipher.doFinal(input);
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Personal model key encryption failed");
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
    }

    public String decrypt(UUID userId, PersonalModelProvider provider, String encrypted) {
        requireAvailable();
        try {
            if (encrypted == null || !encrypted.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(encrypted.substring(3));
            if (packed.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, nonce, userId, provider)
                    .doFinal(packed, 12, packed.length - 12);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("个人密钥无法解密，请重新保存 API Key 或联系管理员");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID userId, PersonalModelProvider provider)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("personal-model:v1:" + userId + ":" + provider.name()).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("管理员尚未配置个人密钥加密服务");
    }
}
