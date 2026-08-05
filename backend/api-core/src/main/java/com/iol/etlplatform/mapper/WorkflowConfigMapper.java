package com.iol.etlplatform.mapper;

import com.iol.etlplatform.dto.workflow.FieldMappingDto;
import com.iol.etlplatform.dto.workflow.WorkflowConfigDto;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.embedded.FieldMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface WorkflowConfigMapper {

    @Mapping(source = "fields", target = "fields", qualifiedByName = "mapFieldsToDto")
    WorkflowConfigDto toDto(WorkflowConfig entity);

    @Mapping(source = "fields", target = "fields", qualifiedByName = "mapFieldsToEntity")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowConfig toEntity(WorkflowConfigDto dto);

    List<WorkflowConfigDto> toDtoList(List<WorkflowConfig> entities);

    FieldMappingDto fieldMappingToDto(FieldMapping fieldMapping);
    FieldMapping fieldMappingToEntity(FieldMappingDto fieldMappingDto);

    @Named("mapFieldsToDto")
    default List<FieldMappingDto> mapFieldsToDto(List<Map<String, Object>> fields) {
        if (fields == null) {
            return new ArrayList<>();
        }
        List<FieldMappingDto> result = new ArrayList<>();
        for (Map<String, Object> fieldMap : fields) {
            FieldMappingDto dto = new FieldMappingDto();
            if (fieldMap.containsKey("sourceName") || fieldMap.containsKey("source_name")) {
                dto.setSourceName((String) fieldMap.getOrDefault("sourceName", fieldMap.get("source_name")));
            }
            if (fieldMap.containsKey("type")) {
                dto.setType((String) fieldMap.get("type"));
            }
            if (fieldMap.containsKey("iolTerm") || fieldMap.containsKey("iol_term")) {
                dto.setIolTerm((String) fieldMap.getOrDefault("iolTerm", fieldMap.get("iol_term")));
            }
            if (fieldMap.containsKey("cleaningRules") || fieldMap.containsKey("cleaning_rules")) {
                dto.setCleaningRules((Map<String, Object>) fieldMap.getOrDefault("cleaningRules", fieldMap.get("cleaning_rules")));
            }
            // Module 5: Enhanced mapping
            if (fieldMap.containsKey("mappingType") || fieldMap.containsKey("mapping_type")) {
                dto.setMappingType((String) fieldMap.getOrDefault("mappingType", fieldMap.get("mapping_type")));
            }
            if (fieldMap.containsKey("sourceFields") || fieldMap.containsKey("source_fields")) {
                dto.setSourceFields((List<String>) fieldMap.getOrDefault("sourceFields", fieldMap.get("source_fields")));
            }
            if (fieldMap.containsKey("expression")) {
                dto.setExpression((String) fieldMap.get("expression"));
            }
            result.add(dto);
        }
        return result;
    }

    @Named("mapFieldsToEntity")
    default List<Map<String, Object>> mapFieldsToEntity(List<FieldMappingDto> fields) {
        if (fields == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (FieldMappingDto dto : fields) {
            Map<String, Object> fieldMap = new HashMap<>();
            if (dto.getSourceName() != null) {
                fieldMap.put("sourceName", dto.getSourceName());
            }
            if (dto.getType() != null) {
                fieldMap.put("type", dto.getType());
            }
            if (dto.getIolTerm() != null) {
                fieldMap.put("iolTerm", dto.getIolTerm());
            }
            if (dto.getCleaningRules() != null) {
                fieldMap.put("cleaningRules", dto.getCleaningRules());
            }
            // Module 5: Enhanced mapping
            if (dto.getMappingType() != null) {
                fieldMap.put("mappingType", dto.getMappingType());
            }
            if (dto.getSourceFields() != null) {
                fieldMap.put("sourceFields", dto.getSourceFields());
            }
            if (dto.getExpression() != null) {
                fieldMap.put("expression", dto.getExpression());
            }
            result.add(fieldMap);
        }
        return result;
    }
}
