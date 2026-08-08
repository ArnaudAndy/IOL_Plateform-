package com.iol.etlplatform.sourcegateway.credential;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.iol.etlplatform.sourcegateway.readmodel.CredentialEnvelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifie que le gateway peut relire ce qu'api-core a chiffre.
 *
 * Le contexte de chiffrement est une donnee AUTHENTIFIEE: il entre dans le
 * calcul cryptographique. Si le gateway le compose differemment d'api-core, ne
 * serait-ce que d'un caractere, le dechiffrement echoue — et cet echec
 * n'apparaitrait qu'a la premiere execution reelle, sur les donnees d'un client.
 */
class CredentialCompatibilityTest {

    /** Cle de developpement, identique au defaut d'api-core. */
    private static final String DEV_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void leContexteEstCompatibleAvecCeluiDApiCore() {
        CredentialContext context =
                new CredentialContext("production", "clinicien@hopital.fr", "conn-42", "jdbc-password");

        // Format attendu par les deux services: champs joints par '|'.
        assertEquals("production|clinicien@hopital.fr|conn-42|jdbc-password",
                     context.associatedData());
    }

    @Test
    void leGatewayRelitUneEnveloppeChiffreeAvecLaMemeCleEtLeMemeContexte() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(DEV_KEY);
        CredentialContext context =
                new CredentialContext("development", "andy@iol.local", "conn-7", "jdbc-password");

        // On fabrique l'enveloppe par le meme algorithme que celui d'api-core,
        // puis on verifie que le chemin de lecture du gateway la retrouve.
        String secret = "m0t-De-P@sse-Source";
        CredentialEnvelope envelope = chiffrerCommeApiCore(cipher, secret, context);

        assertEquals(secret, cipher.decrypt(envelope, context));
    }

    @Test
    void unContexteDifferentEmpecheLaLecture() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(DEV_KEY);
        CredentialContext original =
                new CredentialContext("development", "andy@iol.local", "conn-7", "jdbc-password");
        CredentialEnvelope envelope = chiffrerCommeApiCore(cipher, "secret", original);

        // Meme secret, meme cle, mais proprietaire different: la lecture doit
        // echouer. C'est ce qui empeche de reutiliser une enveloppe hors de sa
        // ressource d'origine.
        CredentialContext autreProprietaire =
                new CredentialContext("development", "autre@iol.local", "conn-7", "jdbc-password");

        boolean refuse;
        try {
            cipher.decrypt(envelope, autreProprietaire);
            refuse = false;
        } catch (RuntimeException expected) {
            refuse = true;
        }
        assertTrue(refuse, "une enveloppe ne doit pas etre lisible avec un autre contexte");
    }

    @Test
    void lInterfaceDuGatewayNExposePasLeChiffrement() throws Exception {
        boolean aUnEncrypt = java.util.Arrays.stream(CredentialCipher.class.getMethods())
                .anyMatch(method -> method.getName().equals("encrypt"));

        assertTrue(!aUnEncrypt,
                "la politique Vault du gateway n'accorde que transit/decrypt: "
                        + "exposer encrypt() produirait du code mort qui echouerait en production");
    }

    @Test
    void laPolitiqueVaultRefuseLeChiffrement() throws Exception {
        Path policy = Path.of("..", "vault", "policies", "iol-source-gateway.hcl");
        Assumptions.assumeTrue(Files.exists(policy), "politique absente de ce contexte de build");

        String contenu = Files.readString(policy);
        assertTrue(contenu.contains("transit/decrypt/"), "le dechiffrement doit etre autorise");
        assertTrue(!contenu.contains("transit/encrypt/"), "le chiffrement doit rester interdit");
        assertTrue(!contenu.contains("transit/rewrap/"), "la reencapsulation doit rester interdite");
    }

    /**
     * Reproduit le chiffrement d'api-core: AES-GCM, IV de 12 octets prefixe au
     * message, contexte en donnee associee, prefixe de format "aes-gcm:v1:".
     */
    private CredentialEnvelope chiffrerCommeApiCore(
            AesGcmCredentialCipher cipher, String plaintext, CredentialContext context) {
        byte[] key = java.util.Base64.getDecoder().decode(DEV_KEY);
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE,
                     new javax.crypto.spec.SecretKeySpec(key, "AES"),
                     new javax.crypto.spec.GCMParameterSpec(128, iv));
            aes.updateAAD(context.associatedData().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] chiffre = aes.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] payload = java.nio.ByteBuffer.allocate(iv.length + chiffre.length)
                    .put(iv).put(chiffre).array();
            CredentialEnvelope envelope = new CredentialEnvelope();
            // Le dechiffreur refuse une enveloppe dont le fournisseur ne
            // correspond pas: on declare donc celui qu'il annonce lui-meme.
            envelope.setProvider(cipher.provider());
            envelope.setKeyName("local-development-key");
            envelope.setCiphertext("aes-gcm:v1:" + java.util.Base64.getEncoder().encodeToString(payload));
            envelope.setKeyVersion(1);
            envelope.setSchemaVersion(1);
            return envelope;
        } catch (Exception error) {
            throw new IllegalStateException("Chiffrement de test impossible", error);
        }
    }
}
