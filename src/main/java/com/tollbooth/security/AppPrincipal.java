package com.tollbooth.security;

/**
 * "Who is calling": the client app that owns the API key on this request.
 * The filter creates it; controllers receive it with @AuthenticationPrincipal.
 */
public record AppPrincipal(Long appId, String appName) {
}
