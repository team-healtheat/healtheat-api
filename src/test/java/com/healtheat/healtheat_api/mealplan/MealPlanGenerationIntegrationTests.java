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
    @Test void refusesUnverifiedAllergyBeforeCallingModel() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(client);
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
    @Test void rejectsAllergenAliasAndDoesNotSavePlan() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.PRESENT,List.of("새우")));
        var base=generated();
        var meals=base.meals().stream().map(m -> new GeneratedMealPlan.Meal(m.mealType(),m.sourceType(),m.recommendationReason(),
            m.items().stream().map(i -> new GeneratedMealPlan.Item(i.foodName(),i.foodRole(),i.preparationType(),i.servingDescription(),i.recipe(),List.of("대하"))).toList())).toList();
        when(client.generate(any())).thenReturn(new GeneratedMealPlan.Plan(meals));
        mvc.perform(post("/api/meal-plans/generate").cookie(cookie(id)).with(csrf())).andExpect(status().isBadGateway());
        mvc.perform(get("/api/meal-plans/today").cookie(cookie(id))).andExpect(status().isNoContent());
    }
}
