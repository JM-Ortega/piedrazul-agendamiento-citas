package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String KEY_ENV = "NOTIFICATION_ENCRYPTION_KEY";

    private static final String VERSION_PREFIX = "{v1}:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private static final SecureRandom secureRandom = new SecureRandom();

    private volatile SecretKeySpec cachedKeySpec;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    keySpec(),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            byte[] cipherText = cipher.doFinal(
                    attribute.getBytes(StandardCharsets.UTF_8)
            );

            ByteBuffer buffer = ByteBuffer.allocate(
                    iv.length + cipherText.length
            );

            buffer.put(iv);
            buffer.put(cipherText);

            return VERSION_PREFIX
                    + Base64.getEncoder().encodeToString(buffer.array());

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Error cifrando dato sensible de notificación",
                    exception
            );
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }

        // Para soportar rotación de claves en el futuro,
        // agregar aquí lógica por versión: {v2}, {v3}, etc.
        if (!dbData.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException(
                    "Versión de cifrado no soportada para dato sensible de notificación"
            );
        }

        try {
            String encoded = dbData.substring(VERSION_PREFIX.length());

            byte[] encrypted = Base64.getDecoder().decode(encoded);

            ByteBuffer buffer = ByteBuffer.wrap(encrypted);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keySpec(),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            byte[] plainText = cipher.doFinal(cipherText);

            return new String(
                    plainText,
                    StandardCharsets.UTF_8
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Error descifrando dato sensible de notificación",
                    exception
            );
        }
    }

    private SecretKeySpec keySpec() {
        if (cachedKeySpec == null) {
            synchronized (this) {
                if (cachedKeySpec == null) {
                    cachedKeySpec = loadKeySpec();
                }
            }
        }

        return cachedKeySpec;
    }

    private SecretKeySpec loadKeySpec() {
        String key = System.getenv(KEY_ENV);

        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Falta configurar la variable de entorno "
                            + KEY_ENV
            );
        }

        byte[] keyBytes = Base64.getDecoder().decode(key);

        if (keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(
                    "La variable "
                            + KEY_ENV
                            + " debe ser una clave AES-256 en Base64"
            );
        }

        return new SecretKeySpec(keyBytes, "AES");
    }
}