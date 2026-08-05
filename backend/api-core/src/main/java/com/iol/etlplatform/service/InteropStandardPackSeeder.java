package com.iol.etlplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.entity.StandardTerm;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.StandardTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.interop.standard-packs.seed-enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class InteropStandardPackSeeder implements ApplicationRunner {

    private final ObjectMapper objectMapper;
    private final StandardRepository standardRepository;
    private final StandardTermRepository standardTermRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var resource = new ClassPathResource("interop-standard-packs.json");
        List<PackDefinition> packs;
        try (var input = resource.getInputStream()) {
            packs = objectMapper.readValue(input, new TypeReference<>() { });
        }
        for (PackDefinition pack : packs) {
            seedPack(pack);
        }
    }

    private void seedPack(PackDefinition pack) {
        LocalDateTime now = LocalDateTime.now();
        Standard standard = standardRepository.findById(pack.id())
                .orElseGet(() -> standardRepository.save(Standard.builder()
                        .id(pack.id())
                        .name(pack.name())
                        .description(pack.description())
                        .domain(Standard.StandardDomain.valueOf(pack.domain()))
                        .version(pack.version())
                        .termCount(pack.terms().size())
                        .status(Standard.StandardStatus.ACTIVE)
                        .referenceUrl(pack.referenceUrl())
                        .createdAt(now)
                        .updatedAt(now)
                        .createdBy("system:interop-standard-pack")
                        .build()));

        for (TermDefinition term : pack.terms()) {
            if (standardTermRepository
                    .findByStandardIdAndTermName(standard.getId(), term.name())
                    .isPresent()) {
                continue;
            }
            standardTermRepository.save(StandardTerm.builder()
                    .id("term_" + pack.id() + "_" + term.name())
                    .standardId(standard.getId())
                    .termName(term.name())
                    .description("Canonical " + pack.name() + " field: " + term.name())
                    .dataType(StandardTerm.DataType.valueOf(term.type()))
                    .required(term.required())
                    .systemMappings(Map.of(pack.sourceSystem(), term.name()))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        log.info("Pack de norme interop disponible: {} ({})", pack.name(), pack.id());
    }

    private record PackDefinition(
            String id,
            String name,
            String description,
            String domain,
            String version,
            String referenceUrl,
            String sourceSystem,
            List<TermDefinition> terms) {
    }

    private record TermDefinition(
            String name,
            String type,
            boolean required) {
    }
}
