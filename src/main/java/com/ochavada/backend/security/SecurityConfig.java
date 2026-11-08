package com.ochavada.backend.security;

import com.ochavada.backend.config.CorsProperties;
import com.ochavada.backend.security.jwt.RoleClaimJwtAuthenticationConverter;
import com.ochavada.backend.security.web.ProblemDetailsAccessDeniedHandler;
import com.ochavada.backend.security.web.ProblemDetailsAuthenticationEntryPoint;
import com.ochavada.backend.security.web.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Seguridad HTTP de la API REST.
 * <ul>
 *     <li>Sin estado: sin sesión HTTP ni cookies de sesión; cada petición se autentica con
 *     {@code Authorization: Bearer <jwt>} (filtro {@code BearerTokenAuthenticationFilter}).</li>
 *     <li>CSRF desactivado: la API no usa autenticación ambiental por cookie. El refresh token viaja en
 *     una cookie HttpOnly {@code SameSite=Strict} restringida a {@code /api/v1/auth}.</li>
 *     <li>Errores 401/403 en formato RFC 7807 ({@code application/problem+json}).</li>
 *     <li>Denegación por defecto: cualquier ruta no declarada devuelve 401/403.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] STAFF_ROLES = {
            SecurityRoles.ADMIN, SecurityRoles.RECEPTIONIST, SecurityRoles.HOUSEKEEPING};

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      RoleClaimJwtAuthenticationConverter jwtAuthenticationConverter,
                                                      ProblemDetailsAuthenticationEntryPoint authenticationEntryPoint,
                                                      ProblemDetailsAccessDeniedHandler accessDeniedHandler,
                                                      BearerTokenResolver bearerTokenResolver) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PublicEndpoints.MATCHER).permitAll()
                        // Portal del huésped: solo tokens de tipo guest
                        .requestMatchers("/api/v1/guest/**").hasRole(SecurityRoles.GUEST)
                        // Administración (usuarios, configuración, facturación): solo ADMIN
                        .requestMatchers("/api/v1/admin/**").hasRole(SecurityRoles.ADMIN)
                        // Backoffice/PMS: cualquier rol de personal; el detalle se afina con @PreAuthorize
                        .requestMatchers("/api/v1/backoffice/**").hasAnyRole(STAFF_ROLES)
                        .requestMatchers("/api/v1/auth/me").hasAnyRole(STAFF_ROLES)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Solo lee el bearer token de la cabecera {@code Authorization} (nunca de parámetros de URL) y lo
     * ignora en endpoints públicos.
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        delegate.setAllowUriQueryParameter(false);
        delegate.setAllowFormEncodedBodyParameter(false);
        return (HttpServletRequest request) ->
                PublicEndpoints.MATCHER.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * ADMIN hereda los permisos de RECEPTIONIST y este los de HOUSEKEEPING. GUEST queda fuera de la jerarquía.
     */
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(SecurityRoles.ADMIN).implies(SecurityRoles.RECEPTIONIST)
                .role(SecurityRoles.RECEPTIONIST).implies(SecurityRoles.HOUSEKEEPING)
                .build();
    }

    /**
     * BCrypt por defecto, con prefijo {@code {bcrypt}} para poder migrar de algoritmo sin invalidar contraseñas.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Gestor usado exclusivamente por el endpoint de login (usuario + contraseña).
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                HttpHeaders.ACCEPT_LANGUAGE, "X-Requested-With", "Idempotency-Key", "X-Checkout-Token"));
        configuration.setExposedHeaders(List.of(HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.LOCATION,
                HttpHeaders.RETRY_AFTER));
        // Necesario para que el navegador envíe la cookie del refresh token desde el frontend.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(properties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
