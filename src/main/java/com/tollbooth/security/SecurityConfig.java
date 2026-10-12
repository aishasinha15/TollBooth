package com.tollbooth.security;

import com.tollbooth.repository.ApiKeyRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security rules: which URLs are public, which need a valid API key,
 * and where our ApiKeyAuthFilter sits in the chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyRepository apiKeys) throws Exception {
        http
                // CSRF protects browser sessions with cookies. Our API uses keys, not cookies, so it's off.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Stateless: no login sessions. Every request must bring its own key.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: status page, health check, Swagger, and Spring's error page
                        .requestMatchers(
                                "/v1/status",
                                "/actuator/health", "/actuator/health/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/error"
                        ).permitAll()
                        // Everything else needs a valid API key
                        .anyRequest().authenticated())
                // No key on a protected URL -> clean JSON 401 instead of Spring's default
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        JsonErrors.write(response, 401, "Missing X-Api-Key header")))
                // Run our key check early in the chain
                .addFilterBefore(new ApiKeyAuthFilter(apiKeys), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
