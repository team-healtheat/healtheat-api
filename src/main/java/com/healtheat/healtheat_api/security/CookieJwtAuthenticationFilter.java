package com.healtheat.healtheat_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates only the access cookie without disabling CSRF for cookie requests. */
public class CookieJwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtDecoder decoder;
    public CookieJwtAuthenticationFilter(JwtDecoder decoder) { this.decoder = decoder; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (!"access_token".equals(cookie.getName())) continue;
                try {
                    Jwt jwt = decoder.decode(cookie.getValue());
                    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
                } catch (JwtException ex) {
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }
}
