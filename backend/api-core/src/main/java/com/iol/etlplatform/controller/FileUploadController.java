package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.file.UploadedFileResponse;
import com.iol.etlplatform.service.UploadedFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "Fichiers", description = "Chargement des sources fichiers ETL")
@SecurityRequirement(name = "bearerAuth")
public class FileUploadController {

    private final UploadedFileService uploadedFileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Charger un fichier source CSV, Excel ou colonne")
    public ResponseEntity<ApiResponse<UploadedFileResponse>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(new ApiResponse<>("Fichier charge.", uploadedFileService.store(file)));
    }
}
