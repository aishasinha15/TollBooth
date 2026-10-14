package com.tollbooth.security;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Guards /admin/**. The operator's key comes from the TOLLBOOTH_ADMIN_KEY environment variable,
 * never from a file in the repository. If it isn't set, the admin API is switched off.
 *
 * Keys are compared with MessageDigest.isEqual, which takes the same time whether the first or
 * the last character differs, so an attacker can't guess the key from how fast "no" comes back.
 */
public class AdminKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Key";

    private final byte[] adminKeyHash; // null = admin API disabled

    public AdminKeyAuthFilter(String adminKey) {
        this.adminKeyHash = adminKey == null || adminKey.isBlank()
                ? null
                : Hashing.sha256Hex(adminKey.trim()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (adminKeyHash == null) {
            JsonErrors.write(response, 401, "Admin API disabled: set the TOLLBOOTH_ADMIN_KEY environment variable");
            return;
        }
        String given = request.getHeader(HEADER);
        if (given == null || given.isBlank()) {
            JsonErrors.write(response, 401, "Missing X-Admin-Key header");
            return;
        }
        byte[] givenHash = Hashing.sha256Hex(given.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(adminKeyHash, givenHash)) {
            JsonErrors.write(response, 401, "Invalid admin key");
            return;
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
