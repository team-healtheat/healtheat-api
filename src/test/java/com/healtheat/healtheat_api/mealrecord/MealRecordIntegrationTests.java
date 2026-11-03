package com.healtheat.healtheat_api.mealrecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class MealRecordIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired MealRecordService service;
    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId(); }
    private Cookie cookie(long id) {
        var now=Instant.now();
        return new Cookie("access_token",encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    private long recommendation(long userId, LocalDate date, boolean active) {
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,userId,date);
        Long rec=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current) values (?,'LUNCH','HOME_COOKED',?) returning id",Long.class,plan,active);
        db.update("insert into meal_items(meal_recommendation_id,food_name,serving_description) values (?,'밥','추천량'),(?,'두부','추천량')",rec,rec);
        return rec;
    }
    @Test void copiesRecommendedFoodOnceWithoutAssumingPortions() throws Exception {
        long owner=user(), other=user(); long rec=recommendation(owner,LocalDate.of(2026,11,2),false);
        String body="{\"recommendationId\":"+rec+"}";
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        var first=mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mealDate").value("2026-11-02"))
            .andExpect(jsonPath("$.fromRecommendation").value(true)).andExpect(jsonPath("$.recommendationId").value(rec))
            .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].foodName").value("밥"))
            .andExpect(jsonPath("$.items[0].servingDescription").doesNotExist()).andExpect(jsonPath("$.createdAt").isNotEmpty()).andReturn();
        long recordId=json.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(recordId));
        db.update("update meal_items set food_name='변경' where meal_recommendation_id=?",rec);
        mvc.perform(get("/api/meal-records").cookie(cookie(owner)).param("from","2026-11-02").param("to","2026-11-02"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].items[0].foodName").value("밥"));
        mvc.perform(get("/api/meal-records").cookie(cookie(other))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertEquals(1L,db.queryForObject("select count(*) from meal_records where recommendation_id=?",Long.class,rec));
    }
    @Test void directRecordsCanRepeatAndDateFiltersAreInclusive() throws Exception {
        long id=user(); Cookie auth=cookie(id);
        String body="{\"mealDate\":\"2026-11-03\",\"mealType\":\"DINNER\",\"items\":[{\"foodName\":\" 김치찌개 \",\"servingDescription\":\"반 그릇\"}]}";
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.fromRecommendation").value(false)).andExpect(jsonPath("$.recommendationId").doesNotExist())
            .andExpect(jsonPath("$.items[0].foodName").value("김치찌개")).andExpect(jsonPath("$.items[0].servingDescription").value("반 그릇"));
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/meal-records").cookie(auth).param("from","2026-11-03").param("to","2026-11-03"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/meal-records").cookie(auth).param("to","2026-11-02")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/meal-records").cookie(auth).param("from","2026-11-04").param("to","2026-11-03")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/meal-records")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mealDate\":\"2026-11-03\",\"mealType\":\"DINNER\",\"items\":[]}")).andExpect(status().isBadRequest());
    }
    @Test void concurrentRecommendationRequestsCreateOneRecord() throws Exception {
        long id=user(), rec=recommendation(id,LocalDate.of(2026,11,1),true);
        var start=new CountDownLatch(1); var ready=new CountDownLatch(2); var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<MealRecordDtos.Record> task=() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return service.createFromRecommendation(id,rec); };
            var first=executor.submit(task); var second=executor.submit(task);
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            assertEquals(first.get(30,TimeUnit.SECONDS).id(),second.get(30,TimeUnit.SECONDS).id());
            assertEquals(1L,db.queryForObject("select count(*) from meal_records where recommendation_id=?",Long.class,rec));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
