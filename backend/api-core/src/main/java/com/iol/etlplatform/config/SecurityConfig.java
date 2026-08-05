package com.iol.etlplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.iol.etlplatform.security.JwtAuthenticationFilter;
import com.iol.etlplatform.security.ApiAuditFilter;
import com.iol.etlplatform.security.KeycloakJwtAuthenticationConverter;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiAuditFilter apiAuditFilter;
    private final UserDetailsService userDetailsService;
    private final KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;

    @Value("${app.auth.mode:LOCAL}")
    private String authMode;

    @Value("${app.security.allowed-origins:http://localhost:5173,http://localhost:3000,http://localhost,http://127.0.0.1:5173}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean keycloak = "KEYCLOAK".equalsIgnoreCase(authMode);
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> {
                        if (keycloak) {
                            auth.requestMatchers("/api/auth/**").denyAll();
                            auth.requestMatchers("/api/internal/interop/**").hasRole("SERVICE_MEDIATOR");
                            auth.requestMatchers("/api/internal/runtime-credentials/**").hasRole("SERVICE_PIPELINE");
                        } else {
                            auth.requestMatchers("/api/auth/**").permitAll();
                            auth.requestMatchers("/api/internal/interop/**").permitAll();
                            auth.requestMatchers("/api/internal/runtime-credentials/**").permitAll();
                        }
                        // Endpoints publics - Swagger/OpenAPI Documentation
                        auth.requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/livez",
                                "/readyz",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/api-docs",
                                "/api-docs/**",
                                "/webjars/**"
                        ).permitAll()
                        // Authenticated application routes - explicit security map
                        .requestMatchers(HttpMethod.GET, "/api/workflows/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/discover").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/draft").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/execute").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/workflows").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.PUT, "/api/workflows/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/workflows/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/workflow-templates/**").hasAnyRole("ADMIN", "USER")

                        .requestMatchers(HttpMethod.POST, "/api/ai/chat-context").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/ai/generate-sql").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/ai/generate-contextual-sql").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/ai/generate-aggregation-sql/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/ai/generate-cleaning-sql").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/ai/generate-schema-sql").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/ai/status").hasAnyRole("ADMIN", "USER")

                        .requestMatchers(HttpMethod.POST, "/api/sql/**").hasAnyRole("ADMIN", "USER")

                        .requestMatchers("/api/v1/metadata/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/standards/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/api/v1/audit/**").hasRole("ADMIN")
                        .requestMatchers("/api/orchestrator/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/logs/**").hasAnyRole("ADMIN", "USER")
                        // Connexions de destination: lecture USER+ADMIN, écriture restreinte par @PreAuthorize (ADMIN)
                        .requestMatchers("/api/connections/**").hasAnyRole("ADMIN", "USER")
                        // Tous les autres endpoints nécessitent une authentification
                        .anyRequest().authenticated();
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(401, "Authentification requise."))
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendError(403, "Acces refuse.")))
                ;

        if (keycloak) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                    jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)));
            http.addFilterAfter(apiAuditFilter, BearerTokenAuthenticationFilter.class);
        } else {
            http.authenticationProvider(authenticationProvider())
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(apiAuditFilter, JwtAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(v -> !v.isBlank()).toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "X-IOL-Internal-Secret",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
