package com.healtheat.healtheat_api.mealplan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recipes")
@Tag(name = "레시피")
@SecurityRequirement(name = "accessCookie")
class RecipeController {
    private final MealPlanQueryService plans;
    RecipeController(MealPlanQueryService plans) { this.plans = plans; }

    @GetMapping("/{recipeId}")
    @Operation(summary = "내 식단의 레시피 조회", description = "재료와 조리 순서를 조회합니다. 재추천 이전 식단의 레시피도 조회할 수 있습니다.")
    MealPlanDtos.RecipeDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable long recipeId) {
        return plans.recipeForUser(Long.parseLong(jwt.getSubject()), recipeId);
    }
}
