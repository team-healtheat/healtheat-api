package com.healtheat.healtheat_api;

import com.healtheat.healtheat_api.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    private record Csrf(Cookie cookie, String token) {}
    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    @Test void signupLoginAndProtectedEndpoint() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        Csrf c = csrf();
        String signup = json.writeValueAsString(new Signup(email, "password123", "password123"));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isCreated());
        assertThat(users.findByEmail(email).orElseThrow().getPasswordHash()).startsWith("$2" ).isNotEqualTo("password123");
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isConflict());
        String bad = json.writeValueAsString(new Login(email, "wrong-password"));
        mvc.perform(post("/api/auth/login").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isUnauthorized());
        String login = json.writeValueAsString(new Login(email, "password123"));
        MvcResult result = mvc.perform(post("/api/auth/login").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("access_token=", "HttpOnly", "Secure", "Max-Age=1800", "SameSite=Lax");
        Cookie token = result.getResponse().getCookie("access_token");
        mvc.perform(get("/api/users/me").cookie(token)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token.getValue()+"broken"))).andExpect(status().isUnauthorized());
    }
    @Test void expiredTokenIsRejected() throws Exception {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder().subject("1").issuedAt(now.minusSeconds(4000)).expiresAt(now.minusSeconds(3600)).build())).getTokenValue();
        mvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
    }
    @Test void mismatchedPasswordsAreRejected() throws Exception {
        Csrf c = csrf();
        String signup = json.writeValueAsString(new Signup(UUID.randomUUID()+"@example.test", "password123", "different"));
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isBadRequest());
    }
    private record Signup(String email, String password, String passwordConfirm) {}
    private record Login(String email, String password) {}
}

