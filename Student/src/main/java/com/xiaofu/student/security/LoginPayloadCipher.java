package com.xiaofu.student.security;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 登录 / 注册参数解密 + 时间戳校验。
 *
 * <p>与前端 {@code frontend/public/chunk.a.js}（原 1.js）、{@code chunk.b.js}（原 2.js）严格对应：
 * <ul>
 *   <li>账号 / 用户名 / 邮箱：RC4 对称加密（chunk.b.js 的 {@code jmEncrypt}），
 *       密钥见 {@code app.security.login-account-key}</li>
 *   <li>密码：RSA PKCS#1 v1.5 公钥加密（chunk.a.js 的 {@code jm}），此处用配对私钥解密</li>
 * </ul>
 *
 * <p>被加密的是「明文参数.时间戳(毫秒)」这个整体 —— 密文本身不含可见的时间戳，
 * 所以必须先用对应算法解密，再从明文末尾拆出时间戳。时间戳与服务端双向相差超过
 * {@code app.security.login-timestamp-window-seconds} 秒即判定请求超时。
 *
 * <p>注意：这里只做传输层混淆，真正的凭据校验仍然依赖 BCrypt 密码哈希。
 */
@Component
@RequiredArgsConstructor
public class LoginPayloadCipher {

    private static final Logger log = LoggerFactory.getLogger(LoginPayloadCipher.class);

    /** 明文参数与时间戳的分隔符，与前端保持一致。 */
    private static final char SEPARATOR = '.';

    /** 单个 RSA 密文块的 hex 长度上限（2048 位 = 512），防止构造超长请求消耗 CPU。 */
    private static final int MAX_RSA_BLOCK_HEX = 1024;

    /** RSA 字段整段密文的 hex 长度上限：最多允许 4 个块。 */
    private static final int MAX_RSA_CIPHER_HEX = MAX_RSA_BLOCK_HEX * 4;

    /** 账号类字段（账号 / 用户名 / 邮箱）密文 hex 长度上限。 */
    private static final int MAX_ACCOUNT_HEX = 4096;

    /**
     * 解密后「密码.时间戳」的长度上限。
     * 密码规则是 8-64 个字符（UTF-8 下最多 256 字节），再加分隔符与 13 位毫秒时间戳。
     */
    private static final int MAX_PASSWORD_LENGTH = 512;

    private final AppProperties appProperties;

    private PrivateKey privateKey;
    /** 单个 RSA 密文块的字节数（2048 位密钥 = 256）。 */
    private int rsaBlockBytes;
    private String accountKey;

    @PostConstruct
    void init() {
        String rawKey = appProperties.getSecurity().getLoginRsaPrivateKey();
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException(
                    "app.security.login-rsa-private-key 未配置：登录接口无法解密密码，请配置 PKCS#8 私钥后重启");
        }
        try {
            // 兼容 PKCS#8 Base64 与 PEM 文本两种写法
            String base64 = rawKey
                    .replaceAll("-----BEGIN[^-]*-----", "")
                    .replaceAll("-----END[^-]*-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            rsaBlockBytes = ((RSAPrivateKey) privateKey).getModulus().bitLength() / 8;
        } catch (Exception e) {
            throw new IllegalStateException("app.security.login-rsa-private-key 解析失败，请确认是合法 PKCS#8 私钥", e);
        }
        accountKey = appProperties.getSecurity().getLoginAccountKey();
        if (accountKey == null || accountKey.isEmpty()) {
            throw new IllegalStateException("app.security.login-account-key 未配置");
        }
        log.info("登录参数解密组件初始化完成：RSA 块大小 {} 字节，时间戳窗口 {} 秒",
                rsaBlockBytes, appProperties.getSecurity().getLoginTimestampWindowSeconds());
    }

    /**
     * 解密登录参数并校验时间戳。
     *
     * @param encryptedAccount  账号密文（整段加密，明文为「账号.时间戳」）
     * @param encryptedPassword 密码密文（整段加密，明文为「密码.时间戳」）
     * @return 解密后的明文账号与密码
     * @throws BusinessException 参数格式非法（BAD_REQUEST）或时间戳超窗（LOGIN_TIMEOUT）
     */
    public Credentials decrypt(String encryptedAccount, String encryptedPassword) {
        String account = decodeRc4Field(encryptedAccount, "账号");
        String password = decodeRsaField(encryptedPassword, "密码");
        return new Credentials(account, password);
    }

    /**
     * 解密注册参数并校验时间戳：用户名与邮箱走 RC4，密码走 RSA。
     *
     * <p>明文合法性（用户名规则、邮箱格式、密码强度）不在这里判断，
     * 由 {@code AuthService#register} 在拿到明文后校验。
     *
     * @param encryptedUsername 用户名密文（整段加密，明文为「用户名.时间戳」）
     * @param encryptedEmail    邮箱密文（整段加密，明文为「邮箱.时间戳」）
     * @param encryptedPassword 密码密文（整段加密，明文为「密码.时间戳」）
     * @throws BusinessException 参数格式非法（BAD_REQUEST）或时间戳超窗（LOGIN_TIMEOUT）
     */
    public RegisterCredentials decryptRegister(String encryptedUsername,
                                               String encryptedEmail,
                                               String encryptedPassword) {
        String username = decodeRc4Field(encryptedUsername, "用户名");
        String email = decodeRc4Field(encryptedEmail, "邮箱");
        String password = decodeRsaField(encryptedPassword, "密码");
        return new RegisterCredentials(username, email, password);
    }

    /**
     * RC4 字段：整段解密得到「明文.时间戳」，校验时间戳后返回明文参数。
     * 时间戳是加密内容的一部分，只能解密之后再拆。
     */
    private String decodeRc4Field(String cipherHex, String fieldName) {
        checkHex(cipherHex, MAX_ACCOUNT_HEX);
        String plain = new String(rc4(accountKey, hexToBytes(cipherHex)), StandardCharsets.UTF_8);
        return splitAndVerify(plain, fieldName);
    }

    /** RSA 字段：整段解密得到「明文.时间戳」，校验时间戳后返回明文参数。 */
    private String decodeRsaField(String cipherHex, String fieldName) {
        checkHex(cipherHex, MAX_RSA_CIPHER_HEX);
        return splitAndVerify(decryptRsa(cipherHex), fieldName);
    }

    /** 密文 hex 的基本合法性检查：非空且不超过长度上限。 */
    private void checkHex(String cipherHex, int maxHexLength) {
        if (cipherHex == null || cipherHex.isEmpty() || cipherHex.length() > maxHexLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }
    }

    /**
     * 从解密后的「明文.时间戳」中拆出明文，并做双向窗口校验。
     *
     * <p>取最后一个分隔符：明文本身可能含「.」（例如邮箱），而时间戳一定在末尾。
     */
    private String splitAndVerify(String plain, String fieldName) {
        if (plain == null || plain.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }
        int index = plain.lastIndexOf(SEPARATOR);
        if (index <= 0 || index == plain.length() - 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(plain.substring(index + 1));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }
        if (timestamp <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }

        long windowMillis = appProperties.getSecurity().getLoginTimestampWindowSeconds() * 1000L;
        if (Math.abs(System.currentTimeMillis() - timestamp) > windowMillis) {
            log.warn("请求时间戳超窗：字段={}, 偏差={}ms", fieldName, System.currentTimeMillis() - timestamp);
            throw new BusinessException(ErrorCode.LOGIN_TIMEOUT);
        }
        return plain.substring(0, index);
    }

    /**
     * RSA 私钥解密。前端按 {@code chunkSize} 分块，密文块之间以空格分隔，
     * 这里逐块解密后拼接。每个块的 hex 可能因高位为 0 而短于密钥长度，需要左补零。
     */
    private String decryptRsa(String cipherHex) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);

            StringBuilder plain = new StringBuilder();
            for (String block : cipherHex.split(" ")) {
                if (block.isEmpty()) {
                    continue;
                }
                byte[] raw = hexToBytes(block);
                if (raw.length > rsaBlockBytes) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
                }
                byte[] padded = new byte[rsaBlockBytes];
                System.arraycopy(raw, 0, padded, rsaBlockBytes - raw.length, raw.length);
                plain.append(new String(cipher.doFinal(padded), StandardCharsets.UTF_8));
            }
            if (plain.isEmpty() || plain.length() > MAX_PASSWORD_LENGTH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
            }
            return plain.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // BadPadding / IllegalBlockSize 等一律按参数非法处理，避免向调用方泄露细节
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
        }
    }

    /**
     * RC4 加解密（对称）。
     *
     * <p>与 {@code chunk.b.js} 实现逐行对应：密钥按 UTF-16 码元取模使用
     * （Java 的 {@code charAt} 等价于 JS 的 {@code charCodeAt}），参与运算时取低 8 位。
     */
    private static byte[] rc4(String key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key.charAt(i % key.length()) & 0xFF)) % 256;
            int tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
        }

        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < data.length; k++) {
            i = (i + 1) % 256;
            j = (j + s[i]) % 256;
            int tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
            out[k] = (byte) (data[k] ^ s[(s[i] + s[j]) % 256]);
        }
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        int length = hex.length();
        if (length == 0) {
            return new byte[0];
        }
        // 奇数长度时左补一个 0，兼容省略前导零的密文
        String normalized = (length & 1) == 1 ? "0" + hex : hex;
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(normalized.charAt(i * 2), 16);
            int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "参数不合法");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    /** 解密后的登录凭据。 */
    public record Credentials(String account, String password) {
    }

    /** 解密后的注册凭据。 */
    public record RegisterCredentials(String username, String email, String password) {
    }
}
