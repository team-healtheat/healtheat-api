package com.healtheat.healtheat_api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.ai.HealthAnalysisClient;
import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HealthProfileIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @MockitoBean HealthAnalysisClient analysis;
    Cookie userCookie, otherCookie, csrfCookie;
    String csrfToken;
    @BeforeEach void setup() throws Exception {
        User one = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash"));
        User two = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash"));
        userCookie = jwt(one.getId()); otherCookie = jwt(two.getId());
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        csrfToken = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    }
    private Cookie jwt(long id) {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
            .subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
        return new Cookie("access_token", token);
    }
    @Test void analysisDoesNotPersistAndConfirmationIsIsolated() throws Exception {
        String name = "factor-" + UUID.randomUUID();
        when(analysis.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(
            List.of(new HealthDtos.AnalysisFactor(name, "GOAL", "REPORTED", "원문"))));
        Integer mastersBefore = db.queryForObject("SELECT count(*) FROM health_factors", Integer.class);
        Integer profilesBefore = db.queryForObject("SELECT count(*) FROM user_health_factors", Integer.class);
        mvc.perform(post("/api/health-profiles/analyze").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rawText", "사용자 입력"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].name").value(name))
            .andExpect(jsonPath("$.factors[0].supported").value("UNSUPPORTED"));
        assertThat(db.queryForObject("SELECT count(*) FROM health_factors", Integer.class)).isEqualTo(mastersBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM user_health_factors", Integer.class)).isEqualTo(profilesBefore);
        String confirmed = json.writeValueAsString(Map.of("factors", List.of(Map.of("name", name, "type", "GOAL",
            "reportedStatus", "REPORTED", "originalText", "원문", "active", true))));
        mvc.perform(put("/api/health-profiles").cookie(userCookie).contentType(MediaType.APPLICATION_JSON).content(confirmed)).andExpect(status().isForbidden());
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(confirmed)).andExpect(status().isOk())
            .andExpect(jsonPath("$.factors[0].name").value(name)).andExpect(jsonPath("$.factors[0].active").value(true))
            .andExpect(jsonPath("$.factors[0].supported").value("UNSUPPORTED"));
        mvc.perform(get("/api/health-profiles").cookie(otherCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.factors.length()").value(0));
        mvc.perform(get("/api/health-profiles")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"factors\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].active").value(false));
    }
    @Test void onlyServerMasterControlsSupportedAndExistingFactorCanReactivate() throws Exception {
        String name = "known-" + UUID.randomUUID();
        Long id = db.queryForObject("INSERT INTO health_factors(code, name, type, supported) VALUES (?, ?, ?, 'SUPPORTED') RETURNING id",
            Long.class, "TEST_"+UUID.randomUUID(), name, "GOAL");
        when(analysis.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(
            List.of(new HealthDtos.AnalysisFactor(name, "GOAL", "REPORTED", null))));
        mvc.perform(post("/api/health-profiles/analyze").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"입력\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].factorId").value(id))
            .andExpect(jsonPath("$.factors[0].supported").value("SUPPORTED"));
        String active = json.writeValueAsString(Map.of("factors", List.of(Map.of("factorId", id, "name", name,
            "type", "GOAL", "reportedStatus", "REPORTED", "active", true))));
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(active)).andExpect(status().isOk());
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"factors\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].active").value(false));
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(active)).andExpect(status().isOk())
            .andExpect(jsonPath("$.factors.length()").value(1)).andExpect(jsonPath("$.factors[0].active").value(true));
    }
    @Test void repeatedConfirmationReusesUsersFactorWhenMasterNamesAreAmbiguous() throws Exception {
        String name = "ambiguous-" + UUID.randomUUID();
        db.update("INSERT INTO health_factors(code, name, type, supported) VALUES (?, ?, 'GOAL', 'SUPPORTED')", "TEST_"+UUID.randomUUID(), name);
        db.update("INSERT INTO health_factors(code, name, type, supported) VALUES (?, ?, 'GOAL', 'UNSUPPORTED')", "TEST_"+UUID.randomUUID(), name);
        when(analysis.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(
            List.of(new HealthDtos.AnalysisFactor(name, "GOAL", "REPORTED", null))));
        mvc.perform(post("/api/health-profiles/analyze").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"입력\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.factors[0].supported").value("UNSUPPORTED"));
        String confirmed = json.writeValueAsString(Map.of("factors", List.of(Map.of("name", name, "type", "GOAL", "reportedStatus", "REPORTED", "active", true))));
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(confirmed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.factors.length()").value(1)).andExpect(jsonPath("$.factors[0].supported").value("UNSUPPORTED"));
        }
    }
    @Test void invalidAiResponseIsRejectedWithoutSaving() throws Exception {
        when(analysis.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(
            List.of(new HealthDtos.AnalysisFactor("", "GOAL", "REPORTED", null))));
        mvc.perform(post("/api/health-profiles/analyze").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"입력\"}"))
            .andExpect(status().isBadGateway());
        mvc.perform(get("/api/health-profiles").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.factors.length()").value(0));
    }
    @Test void validationRejectsBlankAndDuplicateFactorsWithoutPartialChanges() throws Exception {
        mvc.perform(post("/api/health-profiles/analyze").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"  \"}")).andExpect(status().isBadRequest());
        var item = Map.of("name", "duplicate", "type", "GOAL", "reportedStatus", "REPORTED", "active", true);
        String body = json.writeValueAsString(Map.of("factors", List.of(item, item)));
        mvc.perform(put("/api/health-profiles").cookie(userCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/health-profiles").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.factors.length()").value(0));
    }
}
