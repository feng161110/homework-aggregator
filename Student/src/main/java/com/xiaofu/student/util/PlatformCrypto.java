//平台账号密码的可逆加密（AES-256-GCM）
package com.xiaofu.student.util;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 平台密码必须「可逆」保存（Cookie 失效后要用它自动重新登录），
 * 因此使用对称加密 AES-256-GCM，而不是 BCrypt。
 * <p>
 * 密钥由 app.platform.secret-key 经 SHA-256 派生成 32 字节；
 * 密文格式为 Base64(iv(12B) || 密文+认证标签)。
 * 密钥变更后旧密文将无法解密，需要用户重新绑定平台。
 */
@Component
public class PlatformCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public PlatformCrypto(AppProperties appProperties) {
        String secret = appProperties.getPlatform().getSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.platform.secret-key 未配置，无法加密平台密码");
        }
        this.keySpec = new SecretKeySpec(sha256(secret), KEY_ALGORITHM);
    }

    /** 加密；入参为 null 时返回 null。 */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CRYPTO_ERROR, "平台密码加密失败");
        }
    }

    /** 解密；入参为空时返回 null。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度不合法");
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CRYPTO_ERROR, "平台密码解密失败（密钥可能已更换）");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
