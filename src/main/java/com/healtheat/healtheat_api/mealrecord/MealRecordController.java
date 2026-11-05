package com.healtheat.healtheat_api.mealrecord;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/meal-records")
@Tag(name="식사 기록") @SecurityRequirement(name="accessCookie")
class MealRecordController {
    private final MealRecordService records;
    MealRecordController(MealRecordService records) { this.records=records; }
    @PostMapping
    @Operation(summary="직접 식사 기록",description="같은 날짜·끼니가 이미 기록되어 있으면 409를 반환합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    MealRecordDtos.Record create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MealRecordDtos.DirectRequest request) {
        return records.createDirect(Long.parseLong(jwt.getSubject()),request);
    }
    @PostMapping("/from-recommendation")
    @Operation(summary="추천 식단 간편 기록",description="같은 추천을 반복 요청하면 기존 기록을 반환합니다. 다른 기록이 이미 있는 끼니는 409를 반환합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    MealRecordDtos.Record fromRecommendation(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MealRecordDtos.RecommendationRequest request) {
        return records.createFromRecommendation(Long.parseLong(jwt.getSubject()),request.recommendationId());
    }
    @PutMapping("/{recordId}")
    @Operation(summary="내 식사 기록 수정",description="기존 기록의 날짜와 끼니를 유지하고 실제 음식 목록을 교체합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    MealRecordDtos.Record update(@AuthenticationPrincipal Jwt jwt,@PathVariable long recordId,@Valid @RequestBody MealRecordDtos.UpdateRequest request) {
        return records.update(Long.parseLong(jwt.getSubject()),recordId,request);
    }
    @GetMapping
    @Operation(summary="내 식사 기록 조회",description="from과 to는 선택이며 포함 범위로 조회합니다. 기록이 있는 날짜와 끼니는 식사 기록이 완료된 상태입니다.")
    List<MealRecordDtos.Record> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return records.list(Long.parseLong(jwt.getSubject()),from,to);
    }
}
