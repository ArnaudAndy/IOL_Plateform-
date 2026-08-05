package com.iol.etlplatform.service.credential;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.iol.etlplatform.entity.CredentialEnvelope;

/**
 * Chiffrement local réservé au développement et aux tests.
 *
 * La production est explicitement bloquée si ce fournisseur est sélectionné.
 */
public final class AesGcmCredentialCipher implements CredentialCipher {
    private static final String PROVIDER = "LOCAL_AES_GCM";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCredentialCipher(String base64Key) {
        try {
            this.key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException error) {
            throw new CredentialCryptoException("CREDENTIAL_AES_KEY doit être encodée en Base64.", error);
        }
        if (key.length != 32) {
            throw new CredentialCryptoException("CREDENTIAL_AES_KEY doit contenir exactement 32 octets.");
        }
    }

    @Override
    public CredentialEnvelope encrypt(String plaintext, CredentialContext context) {
        if (plaintext == null) {
            throw new CredentialCryptoException("Le secret à chiffrer est absent.");
        }
        byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.associatedData().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(clear);
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return CredentialEnvelope.builder()
                    .provider(PROVIDER)
                    .keyName("local-development-key")
                    .ciphertext("aes-gcm:v1:" + Base64.getEncoder().encodeToString(payload))
                    .keyVersion(1)
                    .encryptedAt(Instant.now())
                    .schemaVersion(1)
                    .build();
        } catch (Exception error) {
            throw new CredentialCryptoException("Chiffrement AES-GCM impossible.", error);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    @Override
    public String decrypt(CredentialEnvelope envelope, CredentialContext context) {
        assertEnvelope(envelope);
        try {
            byte[] payload = Base64.getDecoder().decode(envelope.getCiphertext().substring("aes-gcm:v1:".length()));
            if (payload.length <= IV_BYTES) {
                throw new CredentialCryptoException("Enveloppe AES-GCM tronquée.");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.associatedData().getBytes(StandardCharsets.UTF_8));
            byte[] clear = cipher.doFinal(encrypted);
            try {
                return new String(clear, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        } catch (CredentialCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new CredentialCryptoException("Déchiffrement AES-GCM refusé.", error);
        }
    }

    private void assertEnvelope(CredentialEnvelope envelope) {
        if (envelope == null || !PROVIDER.equals(envelope.getProvider())
                || envelope.getCiphertext() == null
                || !envelope.getCiphertext().startsWith("aes-gcm:v1:")) {
            throw new CredentialCryptoException("Enveloppe incompatible avec LOCAL_AES_GCM.");
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }
}
