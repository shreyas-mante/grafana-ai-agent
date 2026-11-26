package com.pulseagent.tenant;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM encryption for integration secrets stored in the database. */
@Service
public class Crypto {

    private static final Logger log = LoggerFactory.getLogger(Crypto.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public Crypto(AppProperties props) {
        String material = props.platform().encryptionKey();
        if (material == null || material.length() < 32) {
            material = props.security().sessionSecret();
            if (material == null || material.length() < 32) {
                byte[] r = new byte[32];
                RANDOM.nextBytes(r);
                material = Base64.getEncoder().encodeToString(r);
                log.warn("APP_ENCRYPTION_KEY is not set: using a temporary key. Saved integration secrets will be unreadable after a restart.");
            } else {
                log.warn("APP_ENCRYPTION_KEY is not set: deriving the encryption key from APP_SESSION_SECRET. Set a separate key in production.");
            }
        }
        this.key = new SecretKeySpec(Hashing.sha256(material), "AES");
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static String randomToken(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = randomBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed");
        }
    }

    /** Returns null when the value can't be decrypted (for example after the key changed). */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith("v1:")) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
