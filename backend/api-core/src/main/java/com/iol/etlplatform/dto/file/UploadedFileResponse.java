package com.iol.etlplatform.dto.file;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UploadedFileResponse {
    private String uploadId;
    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private String scanStatus;
    private String storagePath;
}
