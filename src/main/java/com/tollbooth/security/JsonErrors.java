package com.tollbooth.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Writes a small JSON error directly to the response.
 * Used by the security layer, which runs BEFORE controllers,
 * so the normal exception handler can't help there.
 */
public final class JsonErrors {

    private JsonErrors() {
    }

    /** Message must be a fixed string we wrote (it is not escaped). */
    public static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"status\":" + status + ",\"error\":\"" + (status == 403 ? "Forbidden" : "Unauthorized")
                        + "\",\"message\":\"" + message + "\"}");
    }
}
