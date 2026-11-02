package com.healtheat.healtheat_api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HealthAiProviderIntegrationTests {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final LinkedBlockingQueue<String> RESPONSES = new LinkedBlockingQueue<>();
    static HttpServer server;
    static final java.util.concurrent.atomic.AtomicBoolean UNSUPPORTED_FIELDS = new java.util.concurrent.atomic.AtomicBoolean(false);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            if (request.has("temperature") || request.has("top_p") || request.has("logprobs")) UNSUPPORTED_FIELDS.set(true);
            String content = RESPONSES.poll();
            if (content == null) content = "{}";
            if (content.equals("__DELAY__")) {
                try { Thread.sleep(3000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                content = "{}";
            }
            byte[] output = MAPPER.writeValueAsBytes(Map.of("id", "test", "object", "chat.completion", "created", 1,
                "model", "gpt-5-mini", "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
        server.start();
        registry.add("spring.http.client.read-timeout", () -> "2s");
        registry.add("spring.ai.model.chat", () -> "openai");
        registry.add("spring.ai.openai.api-key", () -> "local-test-only");
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate db;
    @Test void springAiParsesAndValidatesProviderOutputWithoutPersistingCandidates() throws Exception {
        User user = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash"));
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
            .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
        Cookie auth = new Cookie("access_token", token);
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        String csrfToken = MAPPER.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        int before = db.queryForObject("SELECT count(*) FROM user_health_factors", Integer.class);
        RESPONSES.add("{\"factors\":[{\"name\":\"고혈압\",\"type\":\"DIAGNOSIS\",\"reportedStatus\":\"REPORTED\",\"originalText\":\"고혈압\"}]}");
        mvc.perform(post("/api/health-profiles/analyze").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"고혈압이 있어요\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].name").value("고혈압"))
            .andExpect(jsonPath("$.factors[0].supported").value("UNSUPPORTED"));
        RESPONSES.add("{\"factors\":[{\"name\":\"\",\"type\":\"DIAGNOSIS\",\"reportedStatus\":\"REPORTED\"}]}");
        mvc.perform(post("/api/health-profiles/analyze").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"입력\"}"))
            .andExpect(status().isBadGateway());
        RESPONSES.add("__DELAY__");
        mvc.perform(post("/api/health-profiles/analyze").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"입력\"}"))
            .andExpect(status().isGatewayTimeout());
        assertThat(UNSUPPORTED_FIELDS.get()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM user_health_factors", Integer.class)).isEqualTo(before);
    }
}
