package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import com.healtheat.healtheat_api.user.preferences.PreferenceDtos;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class MealPlanGenerationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired PreferenceService preferences;
    @Autowired MealPlanGenerationService generation;
    @Autowired JwtEncoder encoder;
    @MockitoBean MealGenerationClient client;
    private Cookie cookie(long id) {
        var now=Instant.now();
        return new Cookie("access_token",encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    private GeneratedMealPlan.Plan generated() {
        var recipe=new GeneratedMealPlan.Recipe("두부구이",1,10,List.of(new GeneratedMealPlan.Ingredient("두부","1","모")),List.of("두부를 굽는다"));
        var item=new GeneratedMealPlan.Item("두부구이","MAIN","COOK","한 접시",recipe);
        return new GeneratedMealPlan.Plan(List.of(new GeneratedMealPlan.Meal("BREAKFAST","HOME_COOKED","식사",List.of(item)),
            new GeneratedMealPlan.Meal("LUNCH","HOME_COOKED","식사",List.of(item)),new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","식사",List.of(item))));
    }
    @Test void generatesOnceAndPersistsMealsAndRecipes() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(generated());
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id))).andExpect(status().isForbidden());
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.meals.length()").value(3)).andExpect(jsonPath("$.meals[0].items[0].recipe.ingredients[0].name").value("두부"));
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(id))).andExpect(status().isOk())
            .andExpect(jsonPath("$.meals[0].items[0].recipe.steps[0]").value("두부를 굽는다"));
        verify(client,times(1)).generate(any());
    }
    @Test void generationReceivesOnlyOwnCurrentDiningOutRecommendationsFromPreviousSixDays() {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        long other=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(true,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var today=com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today();
        Long recent=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,id,today.minusDays(1));
        Long old=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,id,today.minusDays(7));
        Long foreign=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,other,today.minusDays(1));
        db.update("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current) values (?,'LUNCH','DINING_OUT',true),(?,'LUNCH','DINING_OUT',true),(?,'LUNCH','DINING_OUT',true)",recent,old,foreign);
        when(client.generate(any())).thenReturn(generated());
        generation.generate(id,today);
        verify(client).generate(argThat(c -> c.diningOutRecommendationsLastSixDays()==1));
    }
    @Test void invalidModelResponseDoesNotSaveAPlan() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(new GeneratedMealPlan.Plan(List.of()));
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isBadGateway());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(id))).andExpect(status().isNoContent());
    }
    @Test void invalidIngredientAmountRollsBackAlreadyInsertedRows() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var badRecipe=new GeneratedMealPlan.Recipe("두부구이",1,10,List.of(new GeneratedMealPlan.Ingredient("두부","많이","모")),List.of("굽는다"));
        var badItem=new GeneratedMealPlan.Item("두부구이","MAIN","COOK",null,badRecipe);
        var badMeal=new GeneratedMealPlan.Meal("BREAKFAST","HOME_COOKED","식사",List.of(badItem));
        var original=generated();
        when(client.generate(any())).thenReturn(new GeneratedMealPlan.Plan(List.of(badMeal,original.meals().get(1),original.meals().get(2))));
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isBadGateway());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(id))).andExpect(status().isNoContent());
    }
    @Test void passesConfirmedActiveHealthFactorsToModelWithoutRawText() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        Long factor=db.queryForObject("insert into health_factors(code,name,type,supported) values (?,?,?,?) returning id",Long.class,"TEST_"+UUID.randomUUID(),"당뇨병","DIAGNOSIS","UNSUPPORTED");
        db.update("insert into user_health_factors(user_id,health_factor_id,reported_status,original_text,is_active) values (?,?,?,?,true)",id,factor,"REPORTED","private text");
        when(client.generate(any())).thenReturn(generated());
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        verify(client).generate(argThat(c -> c.healthFactors().size()==1 && c.healthFactors().get(0).name().equals("당뇨병") && c.healthFactors().get(0).supportStatus().equals("UNSUPPORTED")));
    }
    @Test void passesSavedAllergyAndPersistsNamedIngredients() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.PRESENT,List.of("새우","직접입력재료")));
        var base=generated();
        var meals=base.meals().stream().map(m -> new GeneratedMealPlan.Meal(m.mealType(),m.sourceType(),m.recommendationReason(),
            m.items().stream().map(i -> new GeneratedMealPlan.Item(i.foodName(),i.foodRole(),i.preparationType(),i.servingDescription(),i.recipe(),List.of("두부"))).toList())).toList();
        when(client.generate(any())).thenReturn(new GeneratedMealPlan.Plan(meals));
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.meals[0].items[0].explicitIngredients[0]").value("두부"))
            .andExpect(jsonPath("$.allergyNotice").isNotEmpty());
        verify(client).generate(argThat(c -> c.allergens().equals(List.of("새우","직접입력재료"))));
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(id))).andExpect(status().isOk())
            .andExpect(jsonPath("$.meals[0].items[0].explicitIngredients[0]").value("두부"));
    }
    @Test void passesUnansweredAllergyStateWithoutMarkingItAsNone() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),null,null));
        when(client.generate(any())).thenReturn(generated());
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        verify(client).generate(argThat(c -> c.allergyStatus()==PreferenceDtos.AllergyStatus.UNANSWERED && c.allergens().isEmpty()));
    }
    @Test void concurrentGenerationCallsModelOnlyOnceForSameUserAndDate() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var firstStarted=new java.util.concurrent.CountDownLatch(1);
        var releaseFirst=new java.util.concurrent.CountDownLatch(1);
        var secondStarted=new java.util.concurrent.CountDownLatch(1);
        when(client.generate(any())).thenAnswer(invocation -> {
            firstStarted.countDown();
            if (!releaseFirst.await(20,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return generated();
        });
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var date=com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today();
            var first=executor.submit(() -> generation.generate(id,date));
            org.junit.jupiter.api.Assertions.assertTrue(firstStarted.await(20,java.util.concurrent.TimeUnit.SECONDS));
            var second=executor.submit(() -> { secondStarted.countDown(); return generation.generate(id,date); });
            org.junit.jupiter.api.Assertions.assertTrue(secondStarted.await(20,java.util.concurrent.TimeUnit.SECONDS));
            releaseFirst.countDown();
            var firstPlan=first.get(30,java.util.concurrent.TimeUnit.SECONDS);
            var secondPlan=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(firstPlan.id(),secondPlan.id());
            verify(client,times(1)).generate(any());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
    @Test void regeneratesOnlyRequestedMealAndKeepsPreviousVersion() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        long other=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of("두부"),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(generated());
        var before=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        var replacement=new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","새 저녁",List.of(new GeneratedMealPlan.Item("사과","OTHER","READY_TO_EAT","한 개",null)));
        when(client.regenerate(any())).thenReturn(replacement);
        mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/DINNER/regenerate").cookie(cookie(other)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/DINNER/regenerate").cookie(cookie(id)))
            .andExpect(status().isForbidden());
        var result=mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/DINNER/regenerate").cookie(cookie(id)).with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"additionalRequest\":\"가볍게\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meals.length()").value(3)).andReturn();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        var values=json.get("meals");
        for (var item: values) {
            String type=item.get("mealType").asText();
            var previous=before.meals().stream().filter(m -> m.mealType().equals(type)).findFirst().orElseThrow();
            if ("DINNER".equals(type)) {
                org.junit.jupiter.api.Assertions.assertEquals(2,item.get("version").asInt());
                org.junit.jupiter.api.Assertions.assertEquals("사과",item.get("items").get(0).get("foodName").asText());
            } else org.junit.jupiter.api.Assertions.assertEquals(previous.id().longValue(),item.get("id").asLong());
        }
        var previousDinner=before.meals().stream().filter(m -> m.mealType().equals("DINNER")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,db.queryForObject("select is_current from meal_recommendations where id=?",Boolean.class,previousDinner.id()));
        verify(client).regenerate(argThat(c -> c.mealType().equals("DINNER") && c.additionalRequest().equals("가볍게") && c.previousFoodNames().contains("두부구이")));
    }
    @Test void failedRegenerationPreservesExistingPlan() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(generated());
        var before=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        when(client.regenerate(any())).thenReturn(new GeneratedMealPlan.Meal("LUNCH","DINING_OUT","외식",List.of(new GeneratedMealPlan.Item("비빔밥","MAIN","READY_TO_EAT",null,null))));
        mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/LUNCH/regenerate").cookie(cookie(id)).with(csrf())).andExpect(status().isBadGateway());
        long count=db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and is_current=true",Long.class,before.id());
        org.junit.jupiter.api.Assertions.assertEquals(3,count);
        org.junit.jupiter.api.Assertions.assertEquals(3,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=?",Long.class,before.id()));
    }
    @Test void regenerationStorageErrorRollsBackOldRecommendation() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(generated());
        var before=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        var badRecipe=new GeneratedMealPlan.Recipe("반찬",1,10,List.of(new GeneratedMealPlan.Ingredient("두부","조금","모")),List.of("굽는다"));
        var badItem=new GeneratedMealPlan.Item("반찬","SIDE","COOK",null,badRecipe);
        when(client.regenerate(any())).thenReturn(new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","저녁",List.of(badItem)));
        mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/DINNER/regenerate").cookie(cookie(id)).with(csrf())).andExpect(status().isBadGateway());
        var oldDinner=before.meals().stream().filter(m -> m.mealType().equals("DINNER")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,db.queryForObject("select is_current from meal_recommendations where id=?",Boolean.class,oldDinner.id()));
        org.junit.jupiter.api.Assertions.assertEquals(3L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=?",Long.class,before.id()));
    }
    @Test void recipeDetailsAreOwnedAndOldRecommendationsRemainAccessible() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        long other=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        when(client.generate(any())).thenReturn(generated());
        var before=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        var dinner=before.meals().stream().filter(m -> m.mealType().equals("DINNER")).findFirst().orElseThrow();
        long recipeId=dinner.items().get(0).recipe().id();
        mvc.perform(get("/api/recipes/"+recipeId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recipes/"+recipeId).cookie(cookie(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/recipes/999999999").cookie(cookie(id))).andExpect(status().isNotFound());
        mvc.perform(get("/api/recipes/"+recipeId).cookie(cookie(id))).andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("두부구이")).andExpect(jsonPath("$.servings").value(1))
            .andExpect(jsonPath("$.cookingTimeMinutes").value(10)).andExpect(jsonPath("$.ingredients[0].name").value("두부"))
            .andExpect(jsonPath("$.steps[0]").value("두부를 굽는다")).andExpect(jsonPath("$.allergyNotice").isNotEmpty());
        when(client.regenerate(any())).thenReturn(new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","새 저녁",List.of(new GeneratedMealPlan.Item("사과","OTHER","READY_TO_EAT",null,null))));
        mvc.perform(post("/api/meal-plans/"+before.id()+"/meals/DINNER/regenerate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/recipes/"+recipeId).cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("두부구이"));
    }
    @Test void generationAndRegenerationReceiveOnlyRecentActualMeals() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        long other=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var today=com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today();
        Long recent=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,id,today.minusDays(13));
        Long old=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,id,today.minusDays(14));
        Long foreign=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,other,today);
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,'최근 음식'),(?,'오래된 음식'),(?,'다른 사용자 음식')",recent,old,foreign);
        when(client.generate(any())).thenReturn(generated());
        var plan=generation.generate(id,today);
        verify(client).generate(argThat(c -> c.recentMeals().size()==1 && c.recentMeals().get(0).foods().equals(List.of("최근 음식"))));
        when(client.regenerate(any())).thenReturn(new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","새 저녁",List.of(new GeneratedMealPlan.Item("사과","OTHER","READY_TO_EAT",null,null))));
        mvc.perform(post("/api/meal-plans/"+plan.id()+"/meals/DINNER/regenerate").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        verify(client).regenerate(argThat(c -> c.preferences().recentMeals().size()==1 && c.preferences().recentMeals().get(0).foods().equals(List.of("최근 음식"))));
    }
}
