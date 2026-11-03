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
        long owner=user(), other=user(); LocalDate date=MealRecordPolicy.today().minusDays(1); long rec=recommendation(owner,date,false);
        String body="{\"recommendationId\":"+rec+"}";
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        var first=mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mealDate").value(date.toString()))
            .andExpect(jsonPath("$.fromRecommendation").value(true)).andExpect(jsonPath("$.recommendationId").value(rec))
            .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].foodName").value("밥"))
            .andExpect(jsonPath("$.items[0].servingDescription").doesNotExist()).andExpect(jsonPath("$.createdAt").isNotEmpty()).andReturn();
        long recordId=json.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(recordId));
        db.update("update meal_items set food_name='변경' where meal_recommendation_id=?",rec);
        mvc.perform(get("/api/meal-records").cookie(cookie(owner)).param("from",date.toString()).param("to",date.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].items[0].foodName").value("밥"));
        mvc.perform(get("/api/meal-records").cookie(cookie(other))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertEquals(1L,db.queryForObject("select count(*) from meal_records where recommendation_id=?",Long.class,rec));
    }
    @Test void directRecordIsUniquePerMealAndDateFiltersAreInclusive() throws Exception {
        long id=user(); Cookie auth=cookie(id); LocalDate today=MealRecordPolicy.today();
        String body="{\"mealDate\":\""+today+"\",\"mealType\":\"DINNER\",\"items\":[{\"foodName\":\" 김치찌개 \",\"servingDescription\":\"반 그릇\"},{\"foodName\":\"밥\"}]}";
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.fromRecommendation").value(false)).andExpect(jsonPath("$.recommendationId").doesNotExist())
            .andExpect(jsonPath("$.items[0].foodName").value("김치찌개")).andExpect(jsonPath("$.items[0].servingDescription").value("반 그릇"))
            .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get("/api/meal-records").cookie(auth).param("from",today.toString()).param("to",today.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/meal-records").cookie(auth).param("to",today.minusDays(1).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/meal-records").cookie(auth).param("from",today.plusDays(1).toString()).param("to",today.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/meal-records")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/meal-records").cookie(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mealDate\":\""+today+"\",\"mealType\":\"DINNER\",\"items\":[]}")).andExpect(status().isBadRequest());
    }
    @Test void concurrentRecommendationRequestsCreateOneRecord() throws Exception {
        long id=user(), rec=recommendation(id,MealRecordPolicy.today().minusDays(2),true);
        var start=new CountDownLatch(1); var ready=new CountDownLatch(2); var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<MealRecordDtos.Record> task=() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return service.createFromRecommendation(id,rec); };
            var first=executor.submit(task); var second=executor.submit(task);
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            assertEquals(first.get(30,TimeUnit.SECONDS).id(),second.get(30,TimeUnit.SECONDS).id());
            assertEquals(1L,db.queryForObject("select count(*) from meal_records where recommendation_id=?",Long.class,rec));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void recommendationThenAnotherChoiceOrAnotherRecommendationIsRejected() throws Exception {
        long id=user(); var date=MealRecordPolicy.today(); long previous=recommendation(id,date,false);
        Long planId=db.queryForObject("select meal_plan_id from meal_recommendations where id=?",Long.class,previous);
        Long replacement=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current) values (?,'LUNCH','HOME_COOKED',true) returning id",Long.class,planId);
        db.update("insert into meal_items(meal_recommendation_id,food_name) values (?,'국')",replacement);
        String first="{\"recommendationId\":"+previous+"}";
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isOk());
        mvc.perform(post("/api/meal-records/from-recommendation").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"recommendationId\":"+replacement+"}"))
            .andExpect(status().isConflict());
        String manual="{\"mealDate\":\""+date+"\",\"mealType\":\"LUNCH\",\"items\":[{\"foodName\":\"김밥\"}]}";
        mvc.perform(post("/api/meal-records").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(manual)).andExpect(status().isConflict());
        mvc.perform(get("/api/meal-records").cookie(cookie(id)).param("from",date.toString()).param("to",date.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mealType").value("LUNCH"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,?,false)",id,date,"LUNCH"));
    }
    @Test void directRecordThenRecommendationIsRejectedButOtherSlotsAreAllowed() throws Exception {
        long id=user(), other=user(); var date=MealRecordPolicy.today().minusDays(1); long rec=recommendation(id,date,true);
        var request=new MealRecordDtos.DirectRequest(date,"LUNCH",null,java.util.List.of(new MealRecordDtos.FoodInput("김밥",null)));
        service.createDirect(id,request);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> service.createFromRecommendation(id,rec)).getStatusCode());
        service.createDirect(id,new MealRecordDtos.DirectRequest(date,"DINNER",null,java.util.List.of(new MealRecordDtos.FoodInput("밥",null))));
        service.createDirect(id,new MealRecordDtos.DirectRequest(date.plusDays(1),"LUNCH",null,java.util.List.of(new MealRecordDtos.FoodInput("밥",null))));
        service.createDirect(other,request);
        assertEquals(3L,db.queryForObject("select count(*) from meal_records where user_id=?",Long.class,id));
    }
    @Test void concurrentRecommendationAndDirectRequestsCannotBothSucceed() throws Exception {
        long id=user(); var date=MealRecordPolicy.today(); long rec=recommendation(id,date,true);
        var start=new CountDownLatch(1); var ready=new CountDownLatch(2); var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<String> a=() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); try { service.createFromRecommendation(id,rec); return "created"; } catch (org.springframework.web.server.ResponseStatusException ex) { return Integer.toString(ex.getStatusCode().value()); } };
            Callable<String> b=() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); try { service.createDirect(id,new MealRecordDtos.DirectRequest(date,"LUNCH",null,java.util.List.of(new MealRecordDtos.FoodInput("밥",null)))); return "created"; } catch (org.springframework.web.server.ResponseStatusException ex) { return Integer.toString(ex.getStatusCode().value()); } };
            var first=executor.submit(a); var second=executor.submit(b); assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            assertEquals(java.util.Set.of("created","409"),java.util.Set.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)));
            assertEquals(1L,db.queryForObject("select count(*) from meal_records where user_id=? and meal_date=? and meal_type='LUNCH'",Long.class,id,date));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
