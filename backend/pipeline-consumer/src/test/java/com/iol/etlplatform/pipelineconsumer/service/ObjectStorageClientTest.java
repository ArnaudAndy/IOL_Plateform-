package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ObjectStorageClientTest {

    @Test
    void deletesOnlyObjectStorageArtifactsAfterSuccessfulAcknowledgement() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ObjectStorageClient client = new ObjectStorageClient();
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "client", s3);
        JsonNode command = new ObjectMapper().readTree("""
                {
                  "sourceDataManifest": [
                    {
                      "transport": "OBJECT_STORAGE",
                      "bucket": "iol-source-data",
                      "objectKey": "source-data/wf/log/0/source.jsonl"
                    },
                    {
                      "transport": "KAFKA_ROW_BATCH",
                      "transferId": "kafka-transfer"
                    }
                  ]
                }
                """);

        int deleted = client.deleteTransferredObjects(command);

        assertEquals(1, deleted);
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertEquals("iol-source-data", request.getValue().bucket());
        assertEquals("source-data/wf/log/0/source.jsonl", request.getValue().key());
    }
}
