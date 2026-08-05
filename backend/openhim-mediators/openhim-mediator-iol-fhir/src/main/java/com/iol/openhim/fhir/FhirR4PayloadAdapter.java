package com.iol.openhim.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.AdaptedPayload;
import com.iol.openhim.runtime.DomainPayloadAdapter;
import com.iol.openhim.runtime.DomainValidationException;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FhirR4PayloadAdapter implements DomainPayloadAdapter {

    private final FhirContext context = FhirContext.forR4Cached();
    private final FhirValidator validator;
    private final ObjectMapper objectMapper;

    public FhirR4PayloadAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.validator = context.newValidator();
        var validationSupport = new ValidationSupportChain(
                new DefaultProfileValidationSupport(context),
                new CommonCodeSystemsTerminologyService(context),
                new InMemoryTerminologyServerValidationSupport(context),
                new SnapshotGeneratingValidationSupport(context));
        this.validator.registerValidatorModule(new FhirInstanceValidator(
                validationSupport));
    }

    @Override
    public AdaptedPayload adapt(
            byte[] body,
            String contentType,
            String requestPath,
            HttpHeaders headers) {
        if (body == null || body.length == 0) {
            throw new DomainValidationException("La charge FHIR est vide.");
        }
        String encoded = new String(body, StandardCharsets.UTF_8);
        try {
            IParser parser = isXml(contentType, encoded)
                    ? context.newXmlParser()
                    : context.newJsonParser();
            parser.setParserErrorHandler(new StrictErrorHandler());
            IBaseResource root = parser.parseResource(encoded);
            validate(root);

            List<Map<String, Object>> records = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (root instanceof Bundle bundle) {
                validateBundleSemantics(bundle);
                for (int index = 0; index < bundle.getEntry().size(); index++) {
                    Bundle.BundleEntryComponent entry = bundle.getEntry().get(index);
                    if (entry.getResource() != null) {
                        records.add(toRecord(
                                entry.getResource(), bundle.getType().toCode(), entry, index));
                    }
                }
                metadata.put("fhirBundleType", bundle.getType().toCode());
            } else if (root instanceof Resource resource) {
                records.add(toRecord(resource, "", null, 0));
            } else {
                throw new DomainValidationException("Type de ressource FHIR R4 non pris en charge.");
            }
            if (records.isEmpty()) {
                throw new DomainValidationException(
                        "Le Bundle FHIR ne contient aucune ressource exploitable.");
            }
            metadata.put("fhirVersion", "R4");
            metadata.put("fhirResourceCount", records.size());
            return new AdaptedPayload(records, metadata);
        } catch (DomainValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainValidationException(
                    "Message FHIR R4 invalide: " + rootMessage(error));
        }
    }

    @Override
    public String standardName() {
        return "HL7 FHIR R4";
    }

    private void validate(IBaseResource resource) {
        ValidationResult result = validator.validateWithResult(resource);
        List<String> issues = result.getMessages().stream()
                .filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
                        || message.getSeverity() == ResultSeverityEnum.FATAL)
                .map(message -> message.getLocationString() + ": " + message.getMessage())
                .limit(100)
                .toList();
        if (!issues.isEmpty()) {
            throw new DomainValidationException("La validation FHIR R4 a échoué.", issues);
        }
    }

    private void validateBundleSemantics(Bundle bundle) {
        if (bundle.getType() != Bundle.BundleType.BATCH
                && bundle.getType() != Bundle.BundleType.TRANSACTION) {
            return;
        }
        List<String> issues = new ArrayList<>();
        for (int index = 0; index < bundle.getEntry().size(); index++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(index);
            if (entry.getResource() != null && !entry.hasRequest()) {
                issues.add("Bundle.entry[" + index
                        + "].request est obligatoire pour un Bundle "
                        + bundle.getType().toCode() + ".");
            }
        }
        if (!issues.isEmpty()) {
            throw new DomainValidationException("Sémantique du Bundle FHIR invalide.", issues);
        }
    }

    private Map<String, Object> toRecord(
            Resource resource,
            String bundleType,
            Bundle.BundleEntryComponent entry,
            int index) throws Exception {
        String json = context.newJsonParser().encodeResourceToString(resource);
        JsonNode tree = objectMapper.readTree(json);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("fhir_resource_type", resource.fhirType());
        record.put("fhir_id", resource.getIdElement().getIdPart());
        record.put("fhir_profiles", resource.getMeta().getProfile().stream()
                .map(profile -> profile.getValueAsString())
                .toList());
        record.put("fhir_resource_json", objectMapper.writeValueAsString(tree));
        record.put("fhir_bundle_type", bundleType);
        record.put("fhir_entry_index", index);
        if (entry != null) {
            record.put("fhir_full_url", entry.getFullUrl());
            record.put("fhir_request_method",
                    entry.hasRequest() && entry.getRequest().hasMethod()
                            ? entry.getRequest().getMethod().toCode() : "");
            record.put("fhir_request_url",
                    entry.hasRequest() ? entry.getRequest().getUrl() : "");
        }
        return record;
    }

    private boolean isXml(String contentType, String body) {
        return (contentType != null && contentType.toLowerCase().contains("xml"))
                || body.stripLeading().startsWith("<");
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null
                ? current.getMessage() : current.getClass().getSimpleName();
    }
}
