package com.tollbooth.security;

import com.tollbooth.domain.ApiKey;
import com.tollbooth.domain.ClientApp;
import com.tollbooth.repository.ApiKeyRepository;
import com.tollbooth.util.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The guard at the gate. Runs once for every request, before any controller.
 *
 *  - No X-Api-Key header  -> do nothing; Spring Security later rejects it if the URL needs a key.
 *  - Unknown/inactive key -> stop right here with 401.
 *  - Valid key            -> remember "this request is from app X" for the rest of the request.
 *
 * Not annotated with @Component on purpose: SecurityConfig creates it, so it runs
 * only inside the security chain (a @Component filter would run twice).
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final ApiKeyRepository apiKeys;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // Hash the incoming key the same way we hashed it when saving, then look it up
        Optional<ApiKey> match = apiKeys.findActiveByHash(Hashing.sha256Hex(rawKey.trim()));
        if (match.isEmpty()) {
            JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
            return;
        }

        ClientApp app = match.get().getClientApp();
        AppPrincipal principal = new AppPrincipal(app.getId(), app.getName(), app.getDailyBudgetUsd());

        // Tell Spring Security: this request is authenticated, and here is who it is
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_APP")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }
}
