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
import com.healtheat.healtheat_api.user.UserRepository;

/** Authenticates only the access cookie without disabling CSRF for cookie requests. */
public class CookieJwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtDecoder decoder;
    private final UserRepository users;
    public CookieJwtAuthenticationFilter(JwtDecoder decoder, UserRepository users) { this.decoder = decoder; this.users = users; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        if (java.util.Set.of("/api/auth/csrf","/api/auth/signup","/api/auth/refresh","/api/auth/login","/api/auth/logout").contains(request.getRequestURI())) {
            chain.doFilter(request,response); return;
        }
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (!"access_token".equals(cookie.getName())) continue;
                try {
                    Jwt jwt = decoder.decode(cookie.getValue());
                    if (!users.existsById(Long.parseLong(jwt.getSubject()))) { response.sendError(HttpServletResponse.SC_UNAUTHORIZED); return; }
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
