package com.iol.etlplatform.security;

import com.iol.etlplatform.entity.AuditLog;
import com.iol.etlplatform.entity.User;
import com.iol.etlplatform.repository.UserRepository;
import com.iol.etlplatform.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiAuditFilter extends OncePerRequestFilter {

    private static final Set<String> NON_RESOURCE_SEGMENTS = Set.of(
            "api", "v1", "run", "execute", "test", "activate", "deprecate",
            "discover", "draft", "validate", "upload", "profile", "password"
    );

    private final AuditService auditService;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return "GET".equals(method)
                || "HEAD".equals(method)
                || "OPTIONS".equals(method)
                || isPublicAuthPath(path)
                || path.startsWith("/api/internal")
                || path.startsWith("/api/v1/audit");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        AuditContext context = contextFor(authentication, request);
        try {
            filterChain.doFilter(request, response);
            if (response.getStatus() >= 400) {
                recordFailure(context, "HTTP " + response.getStatus());
            } else {
                recordSuccess(context);
            }
        } catch (ServletException | IOException | RuntimeException exception) {
            recordFailure(context, safeError(exception));
            throw exception;
        }
    }

    private AuditContext contextFor(Authentication authentication, HttpServletRequest request) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        String userId = user == null ? authentication.getName() : String.valueOf(user.getId());
        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                .orElse("UNKNOWN");
        String path = request.getRequestURI();
        return new AuditContext(
                userId,
                role,
                actionFor(request.getMethod(), path),
                resourceTypeFor(path),
                resourceIdFor(path),
                request.getMethod() + " " + path
        );
    }

    private AuditLog.AuditAction actionFor(String method, String path) {
        if (path.matches(".*/(run|execute|test|validate)(/.*)?$")) return AuditLog.AuditAction.EXECUTE;
        if (path.endsWith("/activate")) return AuditLog.AuditAction.ACTIVATE;
        if (path.endsWith("/deprecate")) return AuditLog.AuditAction.DEPRECATE;
        if ("DELETE".equals(method)) return AuditLog.AuditAction.DELETE;
        if ("PUT".equals(method) || "PATCH".equals(method)) return AuditLog.AuditAction.UPDATE;
        return AuditLog.AuditAction.CREATE;
    }

    private String resourceTypeFor(String path) {
        if (path.startsWith("/api/workflows") || path.startsWith("/api/orchestrator")) return "WORKFLOW";
        if (path.startsWith("/api/connections")) return "CONNECTION";
        if (path.startsWith("/api/v1/standards")) return "STANDARD";
        if (path.startsWith("/api/users")) return "USER";
        if (path.startsWith("/api/auth")) return "USER";
        if (path.startsWith("/api/ai")) return "SQL_ASSISTANT";
        if (path.startsWith("/api/sql")) return "SQL_QUERY";
        if (path.startsWith("/api/files")) return "FILE";
        return "API";
    }

    private String resourceIdFor(String path) {
        String[] segments = path.split("/");
        for (int index = segments.length - 1; index >= 0; index--) {
            String segment = segments[index];
            if (!segment.isBlank() && !NON_RESOURCE_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                if (index > 2) return segment;
                break;
            }
        }
        return null;
    }

    private boolean isPublicAuthPath(String path) {
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/password/forgot")
                || path.equals("/api/auth/password/reset")
                || path.equals("/api/auth/create-initial-admin");
    }

    private void recordSuccess(AuditContext context) {
        try {
            auditService.logAction(
                    context.userId(), context.role(), context.action(),
                    context.resourceType(), context.resourceId(), context.description()
            );
        } catch (RuntimeException auditError) {
            log.warn("Impossible d'enregistrer l'audit de {}", context.description(), auditError);
        }
    }

    private void recordFailure(AuditContext context, String error) {
        try {
            auditService.logFailedAction(
                    context.userId(), context.role(), context.action(),
                    context.resourceType(), context.resourceId(), context.description(), error
            );
        } catch (RuntimeException auditError) {
            log.warn("Impossible d'enregistrer l'echec audite de {}", context.description(), auditError);
        }
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 500));
    }

    private record AuditContext(
            String userId,
            String role,
            AuditLog.AuditAction action,
            String resourceType,
            String resourceId,
            String description
    ) {
    }
}
