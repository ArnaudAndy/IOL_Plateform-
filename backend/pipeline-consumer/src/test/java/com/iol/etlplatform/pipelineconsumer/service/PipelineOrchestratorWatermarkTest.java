package com.iol.etlplatform.pipelineconsumer.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérifie le parsing des watermarks émis par moteur_universel.py dans la sortie Hop
 * (lignes IOL_WATERMARK::&lt;target_table&gt;::&lt;valeur&gt;), qui referme la boucle du watermark.
 */
class PipelineOrchestratorWatermarkTest {

    private final PipelineOrchestrator orchestrator = new PipelineOrchestrator(null);

    @Test
    void parsesTargetTableAndTimestampValue() {
        String log = String.join("\n",
                "2026-07-06 10:00:00 - bronze_loop - Extraction depuis ORACLE",
                "IOL_WATERMARK::stg_oracle::2026-06-15T02:00:00Z",
                "Succes final : 120 lignes dans stg_oracle");

        Map<String, String> wm = orchestrator.parseWatermarks(log);

        assertEquals(1, wm.size());
        assertEquals("2026-06-15T02:00:00Z", wm.get("stg_oracle"),
                "La valeur avec ':' (timestamp) doit être conservée entièrement");
    }

    @Test
    void toleratesHopLogPrefixBeforeMarkerAndParsesMultipleSources() {
        String log = String.join("\n",
                "INFO [HOP] IOL_WATERMARK::stg_oracle::100",
                "some other line",
                "  IOL_WATERMARK::stg_pg::2026-01-01 12:30:00 ");

        Map<String, String> wm = orchestrator.parseWatermarks(log);

        assertEquals(2, wm.size());
        assertEquals("100", wm.get("stg_oracle"));
        assertEquals("2026-01-01 12:30:00", wm.get("stg_pg"), "Le trim retire les espaces de bord");
    }

    @Test
    void returnsEmptyWhenNoMarker() {
        assertTrue(orchestrator.parseWatermarks("aucun watermark ici").isEmpty());
        assertTrue(orchestrator.parseWatermarks(null).isEmpty());
        assertTrue(orchestrator.parseWatermarks("").isEmpty());
    }
}
