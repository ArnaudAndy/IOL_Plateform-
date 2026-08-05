package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.standard.StandardDto;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.exception.GlobalExceptionHandler;
import com.iol.etlplatform.service.StandardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirme que StandardController s'appuie sur un StandardService de nouveau vivant :
 * GET /standards répond 200 (et non plus 500 comme avec le service stubé).
 */
class StandardControllerTest {

    private final StandardService standardService = mock(StandardService.class);
    private final StandardController controller = new StandardController(standardService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getAllStandardsReturns200() {
        when(standardService.getAllStandards()).thenReturn(List.of(
                StandardDto.builder().id("std_1").name("HL7v2")
                        .domain(Standard.StandardDomain.HEALTH).build()));

        ResponseEntity<List<StandardDto>> response = controller.getAllStandards();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(standardService).getAllStandards();
    }

    @Test
    void invalidDomainReturnsHelpful400InsteadOf500() throws Exception {
        Authentication authentication = mock(Authentication.class);

        mockMvc.perform(post("/api/v1/standards")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Norme invalide",
                                  "domain": "testing",
                                  "version": "1.0"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Domaine de norme invalide")));

        verifyNoInteractions(standardService);
    }

    @Test
    void validDomainCreatesStandard() throws Exception {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("admin@iol.test");
        when(standardService.createStandard(any(StandardDto.class), eq("admin@iol.test")))
                .thenReturn(StandardDto.builder()
                        .id("std_finance")
                        .name("Référentiel finance")
                        .domain(Standard.StandardDomain.FINANCE)
                        .status(Standard.StandardStatus.DRAFT)
                        .version("1.0")
                        .build());

        mockMvc.perform(post("/api/v1/standards")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Référentiel finance",
                                  "domain": "FINANCE",
                                  "version": "1.0",
                                  "description": "Contrôles comptables"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("std_finance"))
                .andExpect(jsonPath("$.domain").value("FINANCE"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
