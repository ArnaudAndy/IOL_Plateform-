package com.iol.openhim.iso20022;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.AdaptedPayload;
import com.iol.openhim.runtime.DomainPayloadAdapter;
import com.iol.openhim.runtime.DomainValidationException;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class Iso20022PayloadAdapter implements DomainPayloadAdapter {

    private static final String ISO_NAMESPACE_PREFIX =
            "urn:iso:std:iso:20022:tech:xsd:";

    private final ObjectMapper objectMapper;
    private final Set<String> allowedFamilies;
    private final long maxMessageBytes;

    public Iso20022PayloadAdapter(
            ObjectMapper objectMapper,
            @Value("${mediator.iso20022.allowed-families:}") String allowedFamilies,
            @Value("${mediator.iso20022.max-message-bytes:67108864}") long maxMessageBytes) {
        this.objectMapper = objectMapper;
        this.allowedFamilies = parseFamilies(allowedFamilies);
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public AdaptedPayload adapt(
            byte[] body,
            String contentType,
            String requestPath,
            HttpHeaders headers) {
        if (body == null || body.length == 0) {
            throw new DomainValidationException("La charge ISO 20022 est vide.");
        }
        try {
            List<String> messages = jsonMessages(body, contentType);
            List<Map<String, Object>> records = new ArrayList<>();
            Set<String> messageTypes = new LinkedHashSet<>();
            for (String message : messages) {
                if (message.getBytes(StandardCharsets.UTF_8).length > maxMessageBytes) {
                    throw new DomainValidationException(
                            "Un message ISO 20022 dépasse la taille maximale autorisée.");
                }
                Map<String, Object> record = parseMessage(message);
                records.add(record);
                messageTypes.add(String.valueOf(record.get("iso20022_message_definition_id")));
            }
            return new AdaptedPayload(records, Map.of(
                    "iso20022MessageCount", records.size(),
                    "iso20022MessageTypes", List.copyOf(messageTypes)));
        } catch (DomainValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainValidationException(
                    "Message ISO 20022 invalide: " + rootMessage(error));
        }
    }

    @Override
    public String standardName() {
        return "ISO 20022";
    }

    private Map<String, Object> parseMessage(String xml) throws Exception {
        Document document = secureParse(xml);
        Element documentElement = findIsoDocument(document);
        String namespace = documentElement.getNamespaceURI();
        if (!StringUtils.hasText(namespace) || !namespace.startsWith(ISO_NAMESPACE_PREFIX)) {
            throw new DomainValidationException(
                    "Le namespace du Document n'est pas un namespace ISO 20022.");
        }

        AbstractMX parsed = AbstractMX.parse(xml);
        if (parsed == null || parsed.getMxId() == null) {
            throw new DomainValidationException(
                    "Le type du message ISO 20022 n'a pas pu être identifié.");
        }
        String messageDefinitionId = parsed.getMxId().id();
        String family = messageDefinitionId.contains(".")
                ? messageDefinitionId.substring(0, messageDefinitionId.indexOf('.'))
                : messageDefinitionId;
        if (!allowedFamilies.isEmpty() && !allowedFamilies.contains(family)) {
            throw new DomainValidationException(
                    "La famille ISO 20022 " + family + " n'est pas autorisée par ce canal.");
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("iso20022_message_definition_id", messageDefinitionId);
        record.put("iso20022_business_domain", family);
        record.put("iso20022_namespace", namespace);
        record.put("iso20022_payload_xml", xml);
        record.put("iso20022_payload_json", parsed.toJson());
        return record;
    }

    private Document secureParse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private Element findIsoDocument(Document document) {
        Element root = document.getDocumentElement();
        if ("Document".equals(root.getLocalName())) return root;
        NodeList nodes = document.getElementsByTagNameNS("*", "Document");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element candidate = (Element) nodes.item(index);
            if (candidate.getNamespaceURI() != null
                    && candidate.getNamespaceURI().startsWith(ISO_NAMESPACE_PREFIX)) {
                return candidate;
            }
        }
        throw new DomainValidationException("Élément ISO 20022 Document introuvable.");
    }

    private List<String> jsonMessages(byte[] body, String contentType) throws Exception {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (contentType == null || !contentType.toLowerCase().contains("json")) {
            return List.of(text);
        }
        JsonNode root = objectMapper.readTree(text);
        JsonNode messages = root.isArray() ? root : root.path("messages");
        if (!messages.isArray()) {
            JsonNode xml = root.path("xml");
            if (xml.isTextual()) return List.of(xml.asText());
            throw new DomainValidationException(
                    "La charge JSON ISO 20022 doit contenir xml ou messages[].");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : messages) {
            if (!item.isTextual()) {
                throw new DomainValidationException(
                        "Chaque élément messages[] doit être une chaîne XML.");
            }
            result.add(item.asText());
        }
        if (result.isEmpty()) {
            throw new DomainValidationException("Le lot ISO 20022 est vide.");
        }
        return result;
    }

    private Set<String> parseFamilies(String configured) {
        if (!StringUtils.hasText(configured)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> value.matches("[a-z]{4}"))
                .forEach(result::add);
        return Set.copyOf(result);
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
