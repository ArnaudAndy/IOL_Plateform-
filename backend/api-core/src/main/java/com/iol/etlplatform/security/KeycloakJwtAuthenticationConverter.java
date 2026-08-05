package com.iol.etlplatform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Convertit les rôles realm/client Keycloak en autorités Spring ROLE_*. */
@Component
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        collectRoles(jwt.getClaim("realm_access"), roles);

        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map<?, ?> clients) {
            clients.values().forEach(value -> collectRoles(value, roles));
        }

        Collection<GrantedAuthority> authorities = roles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(String::toUpperCase)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();

        String principal = firstNonBlank(
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("preferred_username"),
                jwt.getSubject());
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }

    private void collectRoles(Object claim, Set<String> roles) {
        if (!(claim instanceof Map<?, ?> map)) return;
        Object values = map.get("roles");
        if (values instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(roles::add);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "unknown";
    }
}
