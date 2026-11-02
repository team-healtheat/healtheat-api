package com.healtheat.healtheat_api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PreferenceIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    Cookie user, other, csrfCookie;
    String csrfToken;
    @BeforeEach void setup() throws Exception {
        user = jwt(users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash")).getId());
        other = jwt(users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash")).getId());
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        csrfToken = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    }
    private Cookie jwt(long id) {
        Instant now = Instant.now();
        return new Cookie("access_token", encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    private String json(Object value) throws Exception { return json.writeValueAsString(value); }
    @Test void defaultsUpdatesAndIsolation() throws Exception {
        mvc.perform(get("/api/users/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/preferences").cookie(user)).andExpect(status().isOk())
            .andExpect(jsonPath("$.allergyStatus").value("UNANSWERED"))
            .andExpect(jsonPath("$.allowDiningOut").doesNotExist()).andExpect(jsonPath("$.allergens.length()").value(0));
        String request = json(Map.of("allowDiningOut", true, "likedFoods", List.of(" 두부 ", "사과"),
            "dislikedFoods", List.of("오이"), "dietaryPreferences", List.of("저염"),
            "allergyStatus", "PRESENT", "allergens", List.of("땅콩")));
        mvc.perform(patch("/api/users/me/preferences").cookie(user).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/users/me/preferences").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
            .andExpect(jsonPath("$.allowDiningOut").value(true)).andExpect(jsonPath("$.likedFoods[0]").value("두부"))
            .andExpect(jsonPath("$.allergyStatus").value("PRESENT")).andExpect(jsonPath("$.allergens[0]").value("땅콩"));
        mvc.perform(get("/api/users/me/preferences").cookie(other)).andExpect(status().isOk())
            .andExpect(jsonPath("$.allergyStatus").value("UNANSWERED")).andExpect(jsonPath("$.likedFoods.length()").value(0));
        mvc.perform(patch("/api/users/me/preferences").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"allowDiningOut\":false,\"likedFoods\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowDiningOut").value(false))
            .andExpect(jsonPath("$.likedFoods.length()").value(0)).andExpect(jsonPath("$.allergens[0]").value("땅콩"));
        for (String state : List.of("NONE")) {
            mvc.perform(patch("/api/users/me/preferences").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("allergyStatus", state, "allergens", List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allergyStatus").value(state))
                .andExpect(jsonPath("$.allergens.length()").value(0));
        }
    }
    @Test void invalidAllergyChangesAndDuplicateListsLeaveExistingSettingsUntouched() throws Exception {
        String valid=json(Map.of("allowDiningOut", true, "allergyStatus", "PRESENT", "allergens", List.of("우유")));
        mvc.perform(patch("/api/users/me/preferences").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
        for (String invalid : List.of("{\"allergyStatus\":\"UNKNOWN\",\"allergens\":[]}",
                "{\"allergyStatus\":\"UNANSWERED\",\"allergens\":[]}",
                "{\"allergyStatus\":\"NONE\"}",
                "{\"allergyStatus\":\"PRESENT\",\"allergens\":[]}",
                "{\"allergyStatus\":\"NONE\",\"allergens\":[\"우유\"]}",
                "{\"allergens\":[\"우유\"]}",
                "{\"likedFoods\":[\"두부\",\" 두부 \"]}")) {
            mvc.perform(patch("/api/users/me/preferences").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/users/me/preferences").cookie(user)).andExpect(status().isOk())
            .andExpect(jsonPath("$.allowDiningOut").value(true)).andExpect(jsonPath("$.allergyStatus").value("PRESENT"))
            .andExpect(jsonPath("$.allergens[0]").value("우유"));
    }
    @Test void basicProfileRequiresAuthenticationAndCsrfAndIsIsolated() throws Exception {
        mvc.perform(get("/api/users/me/profile")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me/profile").cookie(user).contentType(MediaType.APPLICATION_JSON)
            .content("{\"age\":27,\"gender\":\"female\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/users/me/profile").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"age\":27,\"gender\":\"female\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.age").value(27)).andExpect(jsonPath("$.gender").value("female"));
        mvc.perform(get("/api/users/me/profile").cookie(other)).andExpect(status().isOk())
            .andExpect(jsonPath("$.age").doesNotExist()).andExpect(jsonPath("$.gender").doesNotExist());
        mvc.perform(put("/api/users/me/profile").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"age\":-1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/me/profile").cookie(user)).andExpect(status().isOk()).andExpect(jsonPath("$.age").value(27));
        mvc.perform(put("/api/users/me/profile").cookie(user, csrfCookie).header("X-XSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"age\":28,\"gender\":null}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.age").value(28)).andExpect(jsonPath("$.gender").doesNotExist());
    }
}
