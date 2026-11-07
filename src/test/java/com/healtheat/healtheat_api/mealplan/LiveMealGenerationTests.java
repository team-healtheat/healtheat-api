package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import com.healtheat.healtheat_api.user.preferences.PreferenceDtos;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MEAL_TEST", matches="true")
class LiveMealGenerationTests {
    @Autowired UserRepository users;
    @Autowired PreferenceService preferences;
    @Autowired MealPlanGenerationService generation;
    @Autowired MealRegenerationService regeneration;
    @Autowired MealPlanQueryService query;
    @Test void varietyWithPorkPreferenceAndRepeatedRegeneration() {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of("돼지고기"),List.of("버섯"),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var plan=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        System.out.println("VARIETY_INITIAL="+plan.meals().stream().map(m -> m.mealType()+":"+m.items().stream().map(i -> i.foodName()).toList()).toList());
        for(int attempt=1;attempt<=3;attempt++) {
            plan=regeneration.regenerate(id,plan.id(),"DINNER",null);
            var dinner=plan.meals().stream().filter(m -> "DINNER".equals(m.mealType())).findFirst().orElseThrow();
            System.out.println("VARIETY_RETRY_"+attempt+"="+dinner.items().stream().map(i -> i.foodName()).toList());
        }
    }
    @Test void actualProviderGeneratesAndRegeneratesOneMeal() {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of("두부"),List.of("버섯"),List.of(),PreferenceDtos.AllergyStatus.PRESENT,List.of("새우")));
        var created=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        System.out.println("INITIAL_MEALS="+created.meals().stream().map(m -> m.mealType()+":"+m.items().stream().map(i -> i.foodName()).toList()).toList());
        assertEquals(3,created.meals().size());
        assertTrue(created.meals().stream().flatMap(m -> m.items().stream()).allMatch(i -> i.foodName().matches(".*[가-힣].*")),"추천 음식명은 한국어여야 합니다");
        var before=created.meals().stream().filter(m -> "DINNER".equals(m.mealType())).findFirst().orElseThrow();
        var after=regeneration.regenerate(id,created.id(),"DINNER","가벼운 메뉴");
        assertEquals(3,after.meals().size());
        var dinner=after.meals().stream().filter(m -> "DINNER".equals(m.mealType())).findFirst().orElseThrow();
        System.out.println("REGENERATED_DINNER="+dinner.items().stream().map(i -> i.foodName()).toList());
        assertNotEquals(before.id(),dinner.id());
        assertEquals(before.version()+1,dinner.version());
        for (String type : List.of("BREAKFAST","LUNCH")) {
            var old=created.meals().stream().filter(m -> type.equals(m.mealType())).findFirst().orElseThrow();
            var current=after.meals().stream().filter(m -> type.equals(m.mealType())).findFirst().orElseThrow();
            assertEquals(old.id(),current.id());
        }
        assertEquals(after,query.get(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today()));
    }
    @Test void diningOutAllowedRecommendation() {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        preferences.update(id,new PreferenceDtos.UpdateRequest(true,List.of("갈비찜"),List.of("버섯"),List.of("한식"),PreferenceDtos.AllergyStatus.NONE,List.of()));
        var result=generation.generate(id,com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today());
        System.out.println("DINING_OUT_ALLOWED="+result.meals().stream().map(m -> m.mealType()+"/"+m.sourceType()+":"+m.items().stream().map(i -> i.foodName()).toList()).toList());
        assertEquals(3,result.meals().size());
        assertTrue(result.meals().stream().flatMap(m -> m.items().stream()).allMatch(i -> i.foodName().matches(".*[가-힣].*")),"추천 음식명은 한국어여야 합니다");
        assertTrue(result.meals().stream().filter(m -> "DINING_OUT".equals(m.sourceType())).count()<=1);
    }
}
