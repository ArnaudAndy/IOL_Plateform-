package com.iol.etlplatform.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.iol.etlplatform.exception.BadRequestException;

class AiPromptPrivacyGuardTest {
    private final AiPromptPrivacyGuard guard = new AiPromptPrivacyGuard();

    @Test
    void acceptsADataFreeBusinessIntent() {
        assertEquals(
                "Calculer le total par client et trier du plus grand au plus petit",
                guard.validateAndNormalize(
                        "  Calculer  le total par client et trier du plus grand au plus petit  "));
    }

    @Test
    void rejectsLikelyBusinessValuesAndSecrets() {
        assertThrows(BadRequestException.class,
                () -> guard.validateAndNormalize("Filtrer le client jean@example.org"));
        assertThrows(BadRequestException.class,
                () -> guard.validateAndNormalize("Utiliser password=TresSecret"));
        assertThrows(BadRequestException.class,
                () -> guard.validateAndNormalize("Chercher le dossier 123456789"));
        assertThrows(BadRequestException.class,
                () -> guard.validateAndNormalize("Filtrer la date 2026-08-05"));
        assertThrows(BadRequestException.class,
                () -> guard.validateAndNormalize("Voici une ligne {\"patient\":\"Alice\"}"));
    }
}
