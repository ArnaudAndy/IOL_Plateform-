package com.iol.etlplatform.security;

import com.iol.etlplatform.entity.AuditLog;
import com.iol.etlplatform.entity.User;
import com.iol.etlplatform.repository.UserRepository;
import com.iol.etlplatform.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAuditFilterTest {

    private final AuditService auditService = mock(AuditService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApiAuditFilter filter = new ApiAuditFilter(auditService, userRepository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsAnAuthenticatedWorkflowExecutionWithoutReadingItsBody() throws Exception {
        authenticateAdmin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orchestrator/run/wf-1");
        request.setRequestURI("/api/orchestrator/run/wf-1");
        request.setContent("{\"privateData\":\"must-not-be-audited\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(auditService).logAction(
                "42",
                "ADMIN",
                AuditLog.AuditAction.EXECUTE,
                "WORKFLOW",
                "wf-1",
                "POST /api/orchestrator/run/wf-1"
        );
    }

    @Test
    void doesNotRecordReadOnlyRequests() throws Exception {
        authenticateAdmin();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows");
        request.setRequestURI("/api/workflows");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(auditService, never()).logAction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private void authenticateAdmin() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("admin@iol.local", null, "ROLE_ADMIN");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        User user = new User();
        user.setId(42L);
        user.setEmail("admin@iol.local");
        when(userRepository.findByEmail("admin@iol.local")).thenReturn(Optional.of(user));
    }
}
