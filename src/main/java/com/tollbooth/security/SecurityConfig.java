package com.tollbooth.security;

import com.tollbooth.repository.ApiKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security rules: which URLs are public, which need which key,
 * and where our key filters sit in the chain.
 *
 *   /v1/**      app API key (X-Api-Key)          -> role APP
 *   /admin/**   operator key (X-Admin-Key)       -> role ADMIN
 *   dashboard, Swagger, health, status: public (the dashboard asks for an app key itself)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyRepository apiKeys,
                                                   @Value("${tollbooth.admin.api-key:}") String adminKey)
            throws Exception {
        http
                // CSRF protects browser sessions with cookies. Our API uses keys, not cookies, so it's off.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Stateless: no login sessions. Every request must bring its own key.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/dashboard.html", "/favicon.ico",
                                "/v1/status",
                                "/actuator/health", "/actuator/health/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/error"
                        ).permitAll()
                        // An app key can't use the admin API, and the admin key can't ask questions
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v1/**").hasRole("APP")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // No key on a protected URL -> clean JSON 401 instead of Spring's default
                        .authenticationEntryPoint((request, response, authException) ->
                                JsonErrors.write(response, 401, "Missing X-Api-Key header"))
                        // A valid key, but the wrong kind for this URL -> 403
                        .accessDeniedHandler((request, response, deniedException) ->
                                JsonErrors.write(response, 403, "This key can't be used here")))
                // Run our key checks early in the chain
                .addFilterBefore(new AdminKeyAuthFilter(adminKey), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthFilter(apiKeys), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
