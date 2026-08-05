package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.standard.StandardDto;
import com.iol.etlplatform.dto.standard.StandardTermDto;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.entity.StandardTerm;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.StandardTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Service pour la gestion des Standards
 *
 * Responsabilités:
 * - Créer, lire, mettre à jour, supprimer des standards
 * - Gérer les termes d'un standard
 * - Valider les mappages sémantiques
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StandardService {

    private final StandardRepository standardRepository;
    private final StandardTermRepository standardTermRepository;

    // ==================== STANDARDS ====================

    /**
     * Créer un nouveau standard
     */
    @Transactional
    public StandardDto createStandard(StandardDto dto, String userId) {
        log.info("Création d'un standard: {}", dto.getName());

        Standard standard = Standard.builder()
                .id(UUID.randomUUID().toString())
                .name(dto.getName())
                .description(dto.getDescription())
                .domain(dto.getDomain())
                .version(dto.getVersion())
                .status(Standard.StandardStatus.DRAFT)
                .referenceUrl(dto.getReferenceUrl())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(userId)
                .build();

        Standard saved = standardRepository.save(standard);
        log.info("Standard créé avec l'ID: {}", saved.getId());
        return toDto(saved, null);
    }

    /**
     * Récupérer un standard par ID
     */
    @Transactional(readOnly = true)
    public StandardDto getStandardById(String id) {
        log.debug("Récupération du standard: {}", id);

        Standard standard = standardRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Standard non trouvé: {}", id);
                    return new NoSuchElementException("Standard non trouvé: " + id);
                });

        List<StandardTerm> terms = standardTermRepository.findByStandardId(id);
        return toDto(standard, terms);
    }

    /**
     * Récupérer tous les standards actifs d'un domaine
     */
    @Transactional(readOnly = true)
    public List<StandardDto> getStandardsByDomain(Standard.StandardDomain domain) {
        log.debug("Récupération des standards du domaine: {}", domain);

        return standardRepository.findByDomainAndStatus(domain, Standard.StandardStatus.ACTIVE)
                .stream()
                .map(standard -> {
                    List<StandardTerm> terms = standardTermRepository.findByStandardId(standard.getId());
                    return toDto(standard, terms);
                })
                .collect(Collectors.toList());
    }

    /**
     * Récupérer tous les standards
     */
    @Transactional(readOnly = true)
    public List<StandardDto> getAllStandards() {
        log.debug("Récupération de tous les standards");

        return standardRepository.findAll()
                .stream()
                .map(standard -> {
                    List<StandardTerm> terms = standardTermRepository.findByStandardId(standard.getId());
                    return toDto(standard, terms);
                })
                .collect(Collectors.toList());
    }

    /**
     * Mettre à jour un standard
     */
    @Transactional
    public StandardDto updateStandard(String id, StandardDto dto) {
        log.info("Mise à jour du standard: {}", id);

        Standard standard = standardRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Standard non trouvé: " + id));

        if (dto.getName() != null) standard.setName(dto.getName());
        if (dto.getDomain() != null) standard.setDomain(dto.getDomain());
        if (dto.getDescription() != null) standard.setDescription(dto.getDescription());
        if (dto.getVersion() != null) standard.setVersion(dto.getVersion());
        if (dto.getReferenceUrl() != null) standard.setReferenceUrl(dto.getReferenceUrl());
        if (dto.getStatus() != null) standard.setStatus(dto.getStatus());

        standard.setUpdatedAt(LocalDateTime.now());
        Standard updated = standardRepository.save(standard);

        List<StandardTerm> terms = standardTermRepository.findByStandardId(id);
        return toDto(updated, terms);
    }

    /**
     * Activer un standard
     */
    @Transactional
    public StandardDto activateStandard(String id) {
        log.info("Activation du standard: {}", id);

        Standard standard = standardRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Standard non trouvé: " + id));

        standard.setStatus(Standard.StandardStatus.ACTIVE);
        standard.setUpdatedAt(LocalDateTime.now());
        Standard updated = standardRepository.save(standard);

        List<StandardTerm> terms = standardTermRepository.findByStandardId(id);
        return toDto(updated, terms);
    }

    /**
     * Dépublier un standard
     */
    @Transactional
    public StandardDto deprecateStandard(String id) {
        log.info("Dépublication du standard: {}", id);

        Standard standard = standardRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Standard non trouvé: " + id));

        standard.setStatus(Standard.StandardStatus.DEPRECATED);
        standard.setUpdatedAt(LocalDateTime.now());
        Standard updated = standardRepository.save(standard);

        List<StandardTerm> terms = standardTermRepository.findByStandardId(id);
        return toDto(updated, terms);
    }

    // ==================== STANDARD TERMS ====================

    /**
     * Ajouter un terme à un standard
     */
    @Transactional
    public StandardTermDto addTermToStandard(String standardId, StandardTermDto dto) {
        log.info("Ajout du terme {} au standard: {}", dto.getTermName(), standardId);

        // Vérifier que le standard existe
        Standard standard = standardRepository.findById(standardId)
                .orElseThrow(() -> new NoSuchElementException("Standard non trouvé: " + standardId));

        // Vérifier que le terme n'existe pas déjà
        standardTermRepository.findByStandardIdAndTermName(standardId, dto.getTermName())
                .ifPresent(term -> {
                    throw new IllegalArgumentException("Le terme '" + dto.getTermName() + "' existe déjà");
                });

        StandardTerm term = StandardTerm.builder()
                .id(UUID.randomUUID().toString())
                .standardId(standardId)
                .termName(dto.getTermName())
                .description(dto.getDescription())
                .dataType(dto.getDataType())
                .formatRule(dto.getFormatRule())
                .minLength(dto.getMinLength())
                .maxLength(dto.getMaxLength())
                .required(dto.getRequired() != null ? dto.getRequired() : true)
                .enumValues(dto.getEnumValues())
                .precision(dto.getPrecision())
                .scale(dto.getScale())
                .systemMappings(dto.getSystemMappings())
                .exampleValue(dto.getExampleValue())
                .notes(dto.getNotes())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        StandardTerm saved = standardTermRepository.save(term);

        // Mettre à jour le compte de termes dans le standard
        long count = standardTermRepository.countByStandardId(standardId);
        standard.setTermCount((int) count);
        standardRepository.save(standard);

        log.info("Terme créé avec l'ID: {}", saved.getId());
        return termToDto(saved);
    }

    /**
     * Récupérer tous les termes d'un standard
     */
    @Transactional(readOnly = true)
    public List<StandardTermDto> getTermsByStandard(String standardId) {
        log.debug("Récupération des termes du standard: {}", standardId);

        return standardTermRepository.findByStandardId(standardId)
                .stream()
                .map(this::termToDto)
                .collect(Collectors.toList());
    }

    /**
     * Dé-normalise des lignes pivot IOL vers les champs attendus par une norme
     * cible. Sens inverse de la normalisation INBOUND:
     *
     * - entrée pivot: clé = StandardTerm.termName
     * - sortie cible: clé = StandardTerm.systemMappings[targetSystem] si trouvé,
     *   sinon mapping "outbound"/"default", sinon termName.
     *
     * Cette méthode ne transporte pas les champs non déclarés dans le standard
     * cible: la norme reste la source de vérité de ce qui peut être livré.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> denormalizeFromPivot(
            String standardId,
            List<Map<String, Object>> pivotRows,
            String targetSystem) {
        if (standardId == null || standardId.isBlank()) {
            throw new IllegalArgumentException("standardId est obligatoire pour la dé-normalisation OUTBOUND.");
        }
        if (pivotRows == null) {
            throw new IllegalArgumentException("pivotRows est obligatoire pour la dé-normalisation OUTBOUND.");
        }

        if (!standardRepository.existsById(standardId)) {
            throw new NoSuchElementException("Standard non trouvé: " + standardId);
        }

        List<StandardTerm> terms = standardTermRepository.findByStandardId(standardId);
        List<Map<String, Object>> denormalized = new ArrayList<>();

        for (Map<String, Object> row : pivotRows) {
            Map<String, Object> output = new LinkedHashMap<>();
            Map<String, Object> safeRow = row != null ? row : Map.of();

            for (StandardTerm term : terms) {
                String termName = term.getTermName();
                if (termName == null || !safeRow.containsKey(termName)) {
                    continue;
                }
                output.put(resolveOutboundFieldName(term, targetSystem), safeRow.get(termName));
            }
            denormalized.add(output);
        }

        return denormalized;
    }

    private String resolveOutboundFieldName(StandardTerm term, String targetSystem) {
        Map<String, String> mappings = term.getSystemMappings();
        if (mappings == null || mappings.isEmpty()) {
            return term.getTermName();
        }

        String requested = targetSystem != null ? targetSystem.trim() : "";
        if (!requested.isBlank()) {
            String exact = mappings.get(requested);
            if (exact != null && !exact.isBlank()) {
                return exact;
            }
            String lower = mappings.get(requested.toLowerCase());
            if (lower != null && !lower.isBlank()) {
                return lower;
            }
        }

        String outbound = mappings.get("outbound");
        if (outbound != null && !outbound.isBlank()) {
            return outbound;
        }
        String fallback = mappings.get("default");
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        if (requested.isBlank() && mappings.size() == 1) {
            String onlyMapping = mappings.values().iterator().next();
            if (onlyMapping != null && !onlyMapping.isBlank()) {
                return onlyMapping;
            }
        }
        return term.getTermName();
    }

    /**
     * Mettre à jour un terme
     */
    @Transactional
    public StandardTermDto updateTerm(String termId, StandardTermDto dto) {
        log.info("Mise à jour du terme: {}", termId);

        StandardTerm term = standardTermRepository.findById(termId)
                .orElseThrow(() -> new NoSuchElementException("Terme non trouvé: " + termId));

        if (dto.getDescription() != null) term.setDescription(dto.getDescription());
        if (dto.getFormatRule() != null) term.setFormatRule(dto.getFormatRule());
        if (dto.getMinLength() != null) term.setMinLength(dto.getMinLength());
        if (dto.getMaxLength() != null) term.setMaxLength(dto.getMaxLength());
        if (dto.getRequired() != null) term.setRequired(dto.getRequired());
        if (dto.getEnumValues() != null) term.setEnumValues(dto.getEnumValues());
        if (dto.getPrecision() != null) term.setPrecision(dto.getPrecision());
        if (dto.getScale() != null) term.setScale(dto.getScale());
        if (dto.getSystemMappings() != null) term.setSystemMappings(dto.getSystemMappings());
        if (dto.getExampleValue() != null) term.setExampleValue(dto.getExampleValue());
        if (dto.getNotes() != null) term.setNotes(dto.getNotes());

        term.setUpdatedAt(LocalDateTime.now());
        StandardTerm updated = standardTermRepository.save(term);

        return termToDto(updated);
    }

    /**
     * Valider un champ contre les termes d'un standard
     * Retourne true si le champ correspond aux règles du terme
     */
    @Transactional(readOnly = true)
    public boolean validateFieldAgainstStandard(String standardId, String fieldName, String fieldValue, String dataType) {
        log.debug("Validation du champ {} contre le standard {}", fieldName, standardId);

        StandardTerm term = standardTermRepository.findByStandardIdAndTermName(standardId, fieldName)
                .orElseThrow(() -> new NoSuchElementException("Terme non trouvé: " + fieldName));

        // Vérifier le type de données
        if (!term.getDataType().toString().equals(dataType)) {
            log.warn("Typage incorrect: attendu {}, reçu {}", term.getDataType(), dataType);
            return false;
        }

        // Vérifier la longueur
        if (term.getMinLength() != null && fieldValue.length() < term.getMinLength()) {
            log.warn("Valeur trop courte: minimum {}", term.getMinLength());
            return false;
        }

        if (term.getMaxLength() != null && fieldValue.length() > term.getMaxLength()) {
            log.warn("Valeur trop longue: maximum {}", term.getMaxLength());
            return false;
        }

        // Vérifier le format si spécifié
        if (term.getFormatRule() != null && !fieldValue.matches(term.getFormatRule())) {
            log.warn("Format invalide");
            return false;
        }

        // Vérifier les valeurs énumérées
        if (term.getEnumValues() != null && !term.getEnumValues().isEmpty()) {
            if (!term.getEnumValues().contains(fieldValue)) {
                log.warn("Valeur non autorisée");
                return false;
            }
        }

        return true;
    }

    // ==================== CONVERSION UTILITAIRE ====================

    /**
     * Convertir Standard + termes en StandardDto
     */
    private StandardDto toDto(Standard standard, List<StandardTerm> terms) {
        List<StandardTermDto> termDtos = terms != null ? terms.stream()
                .map(this::termToDto)
                .collect(Collectors.toList()) : null;

        return StandardDto.builder()
                .id(standard.getId())
                .name(standard.getName())
                .description(standard.getDescription())
                .domain(standard.getDomain())
                .version(standard.getVersion())
                .termCount(standard.getTermCount())
                .status(standard.getStatus())
                .referenceUrl(standard.getReferenceUrl())
                .createdAt(standard.getCreatedAt())
                .updatedAt(standard.getUpdatedAt())
                .createdBy(standard.getCreatedBy())
                .terms(termDtos)
                .build();
    }

    /**
     * Convertir StandardTerm en StandardTermDto
     */
    private StandardTermDto termToDto(StandardTerm term) {
        return StandardTermDto.builder()
                .id(term.getId())
                .standardId(term.getStandardId())
                .termName(term.getTermName())
                .description(term.getDescription())
                .dataType(term.getDataType())
                .formatRule(term.getFormatRule())
                .minLength(term.getMinLength())
                .maxLength(term.getMaxLength())
                .required(term.getRequired())
                .enumValues(term.getEnumValues())
                .precision(term.getPrecision())
                .scale(term.getScale())
                .systemMappings(term.getSystemMappings())
                .exampleValue(term.getExampleValue())
                .notes(term.getNotes())
                .createdAt(term.getCreatedAt())
                .updatedAt(term.getUpdatedAt())
                .build();
    }
}
