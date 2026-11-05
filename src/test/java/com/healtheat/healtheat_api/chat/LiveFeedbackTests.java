package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_LIVE_FEEDBACK_TEST",matches="true")
class LiveFeedbackTests {
    @Autowired UserRepository users;
    @Autowired HealthProfileService health;
    @Autowired ChatService chat;
    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId(); }
    @Test void koreanAnalysis() {
        long id=user(); var result=health.analyze(id,"당뇨 진단을 받았고 콜레스테롤 수치가 높아요.");
        System.out.println("KOREAN_ANALYSIS="+result.factors().stream().map(f -> f.name()+"/"+f.type()).toList());
        assertFalse(result.factors().isEmpty());
        assertTrue(result.factors().stream().allMatch(f -> f.name().matches(".*[가-힣].*")));
    }
    @Test void healthChangesInOneMessage() {
        long id=user();
        health.confirm(id,new HealthDtos.ConfirmRequest(List.of(new HealthDtos.ConfirmedFactor(null,"콜레스테롤","CONCERN","REPORTED","콜레스테롤 수치가 높음",true))));
        var result=chat.send(id,new ChatDtos.MessageRequest(null,"콜레스테롤이 정상으로 돌아와서 관리 항목에서 빼줘. 그리고 최근 통풍 진단을 받았어."));
        System.out.println("HEALTH_CHANGES="+result.reply());
        assertNotNull(result.updatedHealthProfile());
        assertTrue(result.updatedHealthProfile().factors().stream().anyMatch(f -> f.name().contains("콜레스테롤") && !f.active()));
        assertTrue(result.updatedHealthProfile().factors().stream().anyMatch(f -> f.name().contains("통풍") && f.active()));
    }
}
