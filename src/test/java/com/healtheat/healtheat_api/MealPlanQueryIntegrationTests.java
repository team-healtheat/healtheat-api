package com.healtheat.healtheat_api;

import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class MealPlanQueryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    private Cookie cookie(long id) {
        Instant now = Instant.now();
        return new Cookie("access_token", encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    @Test void todayOnlyReturnsOwnersCurrentSavedPlan() throws Exception {
        long first = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash")).getId();
        long second = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash")).getId();
        mvc.perform(get("/api/meal-plans/today")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(first))).andExpect(status().isNoContent());
        Long plan = db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id", Long.class, first, LocalDate.now());
        Long current = db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current,version) values (?, 'BREAKFAST', 'HOME', true, 2) returning id", Long.class, plan);
        db.update("insert into meal_recommendations(meal_plan_id,meal_type,is_current,version) values (?, 'BREAKFAST', false, 1)", plan);
        db.update("insert into meal_items(meal_recommendation_id,food_name) values (?,?)", current, "밥");
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(second))).andExpect(status().isNoContent());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(first))).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(plan)).andExpect(jsonPath("$.meals.length()").value(1))
            .andExpect(jsonPath("$.meals[0].version").value(2)).andExpect(jsonPath("$.meals[0].items[0].foodName").value("밥"));
    }
}
