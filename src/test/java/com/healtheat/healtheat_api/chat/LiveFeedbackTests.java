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

}
