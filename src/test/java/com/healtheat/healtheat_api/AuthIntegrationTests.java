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

@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
class AuthIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
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
        mvc.perform(post("/api/health-profiles/analyze").cookie(token, c.cookie()).header("X-XSRF-TOKEN", c.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rawText\":\"확인\"}")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token.getValue()+"broken"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token())).andExpect(status().isNoContent());
        MvcResult logout = mvc.perform(post("/api/auth/logout").cookie(token, c.cookie()).header("X-XSRF-TOKEN", c.token()))
            .andExpect(status().isNoContent()).andReturn();
        String expiredCookie = logout.getResponse().getHeaders("Set-Cookie").stream().filter(value -> value.startsWith("access_token=")).findFirst().orElseThrow();
        assertThat(expiredCookie).contains("access_token=", "Path=/", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Lax").doesNotContain("Domain=");
        assertThat(setCookie).contains("Path=/").doesNotContain("Domain=");
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        // Logging out clears the browser cookie; it does not revoke an already issued JWT.
        mvc.perform(get("/api/users/me").cookie(token)).andExpect(status().isOk());
    }
    @Test void expiredTokenIsRejected() throws Exception {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder().subject("1").issuedAt(now.minusSeconds(4000)).expiresAt(now.minusSeconds(3600)).build())).getTokenValue();
        mvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/csrf").cookie(new Cookie("access_token", token))).andExpect(status().isOk());
    }
    @Test void signupAndLoginReturnNickname() throws Exception {
        String email=UUID.randomUUID()+"@example.test"; Csrf c=csrf();
        String signup=json.writeValueAsString(java.util.Map.of("email",email,"password","password123","passwordConfirm","password123","nickname","헬싯"));
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("헬싯"));
        String login=json.writeValueAsString(new Login(email,"password123"));
        var result=mvc.perform(post("/api/auth/login").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("헬싯")).andReturn();
        mvc.perform(get("/api/users/me").cookie(result.getResponse().getCookie("access_token"))).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("헬싯"));
        mvc.perform(patch("/api/users/me/nickname").cookie(result.getResponse().getCookie("access_token"),c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"변경한 이름\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("변경한 이름"));
    }
    @Test void refreshRotatesTokenAndLogoutRevokesIt() throws Exception {
        String email=UUID.randomUUID()+"@example.test"; Csrf c=csrf();
        String signup=json.writeValueAsString(new Signup(email,"password123","password123"));
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isCreated());
        var login=mvc.perform(post("/api/auth/login").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Login(email,"password123")))).andReturn();
        Cookie refresh=login.getResponse().getCookie("refresh_token"); assertThat(refresh).isNotNull();
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isForbidden());
        var rotated=mvc.perform(post("/api/auth/refresh").cookie(refresh,c.cookie(),new Cookie("access_token","expired-or-invalid")).header("X-XSRF-TOKEN",c.token())).andExpect(status().isOk()).andReturn();
        Cookie next=rotated.getResponse().getCookie("refresh_token"); assertThat(next.getValue()).isNotEqualTo(refresh.getValue());
        mvc.perform(post("/api/auth/refresh").cookie(refresh,c.cookie()).header("X-XSRF-TOKEN",c.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(next,c.cookie()).header("X-XSRF-TOKEN",c.token())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").cookie(next,c.cookie()).header("X-XSRF-TOKEN",c.token())).andExpect(status().isUnauthorized());
    }
    @Test void deletionRequiresPasswordAndInvalidatesAllSessions() throws Exception {
        String email=UUID.randomUUID()+"@example.test"; Csrf c=csrf();
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Signup(email,"password123","password123")))).andExpect(status().isCreated());
        var login=mvc.perform(post("/api/auth/login").cookie(c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Login(email,"password123")))).andReturn();
        Cookie access=login.getResponse().getCookie("access_token"), refresh=login.getResponse().getCookie("refresh_token");
        long id=users.findByEmail(email).orElseThrow().getId();
        db.update("insert into user_preferences(user_id) values (?)",id);
        db.update("insert into user_allergies(user_id,allergen_name) values (?,'새우')",id);
        Long session=db.queryForObject("insert into chat_sessions(user_id) values (?) returning id",Long.class,id);
        db.update("insert into chat_messages(chat_session_id,role,content) values (?,'USER','test')",session);
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,current_date) returning id",Long.class,id);
        Long recommendation=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type) values (?,'LUNCH') returning id",Long.class,plan);
        Long item=db.queryForObject("insert into meal_items(meal_recommendation_id,food_name) values (?,'밥') returning id",Long.class,recommendation);
        Long recipe=db.queryForObject("insert into recipes(meal_item_id,title) values (?,'밥') returning id",Long.class,item);
        db.update("insert into recipe_ingredients(recipe_id,ingredient_name) values (?,'쌀')",recipe);
        db.update("insert into recipe_steps(recipe_id,step_number) values (?,1)",recipe);
        Long record=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,recommendation_id,from_recommendation) values (?,current_date,'LUNCH',?,true) returning id",Long.class,id,recommendation);
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,'밥')",record);
        mvc.perform(delete("/api/users/me").cookie(access,c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/me").cookie(access).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/me").cookie(access,c.cookie()).header("X-XSRF-TOKEN",c.token()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").cookie(access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(refresh,c.cookie()).header("X-XSRF-TOKEN",c.token())).andExpect(status().isUnauthorized());
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(db.queryForObject("select count(*) from meal_plans where user_id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("select count(*) from chat_messages where chat_session_id=?",Long.class,session)).isZero();
        assertThat(db.queryForObject("select count(*) from recipe_steps where recipe_id=?",Long.class,recipe)).isZero();
    }
    @Test void mismatchedPasswordsAreRejected() throws Exception {
        Csrf c = csrf();
        String signup = json.writeValueAsString(new Signup(UUID.randomUUID()+"@example.test", "password123", "different"));
        mvc.perform(post("/api/auth/signup").cookie(c.cookie()).header("X-XSRF-TOKEN", c.token()).contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isBadRequest());
    }
    private record Signup(String email, String password, String passwordConfirm) {}
    private record Login(String email, String password) {}
}
