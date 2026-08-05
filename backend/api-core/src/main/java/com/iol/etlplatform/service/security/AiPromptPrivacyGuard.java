package com.iol.etlplatform.service.security;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.iol.etlplatform.exception.BadRequestException;

/** Refuse les formats qui ressemblent a des donnees ou a des secrets avant tout appel LLM. */
@Component
public class AiPromptPrivacyGuard {
    private static final int MAX_INSTRUCTION_LENGTH = 1_000;
    private static final int MAX_LINES = 8;
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
            Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"),
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ][0-9:.+-Z]+)?\\b"),
            Pattern.compile("(?<!\\d)\\d{7,}(?!\\d)"),
            Pattern.compile("(?i)\\b(?:https?|jdbc|mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|oracle|sqlserver)://"),
            Pattern.compile("(?i)\\b(?:password|passwd|secret|token|api[_ -]?key|authorization)\\s*[:=]"),
            Pattern.compile("(?s)(?:\"[^\"\\r\\n]{2,}\"|'[^'\\r\\n]{2,}')"),
            Pattern.compile("(?i)\\b(?:insert\\s+into|copy\\s+.+\\s+from|values\\s*\\()"));

    public String validateAndNormalize(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new BadRequestException("L'instruction SQL est obligatoire.");
        }
        String normalized = instruction.trim().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() > MAX_INSTRUCTION_LENGTH || normalized.lines().count() > MAX_LINES) {
            throw privacyError();
        }
        String compact = normalized.replaceAll("[\\t ]+", " ");
        if (compact.startsWith("{") || compact.startsWith("[") || compact.contains("```")) {
            throw privacyError();
        }
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(compact).find()) {
                throw privacyError();
            }
        }
        return compact;
    }

    private BadRequestException privacyError() {
        return new BadRequestException(
                "Instruction refusee: decrivez uniquement l'intention SQL, sans valeur, exemple, secret ou donnee metier.");
    }
}
