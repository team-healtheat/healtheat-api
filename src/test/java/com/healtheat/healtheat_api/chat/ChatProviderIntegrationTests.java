package com.healtheat.healtheat_api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class ChatProviderIntegrationTests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final LinkedBlockingQueue<String> responses=new LinkedBlockingQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> lastRequest=new java.util.concurrent.atomic.AtomicReference<>();
    private static HttpServer server;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request=JSON.readTree(exchange.getRequestBody()); lastRequest.set(request.toString()); requests.incrementAndGet();
            String content=responses.poll(); if (content==null) content="{}";
            byte[] data=JSON.writeValueAsBytes(Map.of("id","test","object","chat.completion","created",1,"model","gpt-6-luna",
                "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason","stop"))));
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,data.length);
            exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        registry.add("spring.ai.model.chat",() -> "openai");
        registry.add("spring.ai.openai.api-key",() -> "local-test-only");
        registry.add("spring.ai.openai.base-url",() -> "http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterAll static void stop() { if(server!=null) server.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired ChatModel chatModel;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    private Cookie cookie(long id) {
        Instant now=Instant.now(); return new Cookie("access_token",encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    @Test void realSpringAiClientParsesIntentAndReturnsAdviceFromLocalFakeProvider() throws Exception {
        long id=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId();
        var today=com.healtheat.healtheat_api.mealrecord.MealRecordPolicy.today();
        Long record=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,id,today);
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,'두부')",record);
        responses.add("{\"intent\":\"FOOD_CONSULTATION\",\"foodName\":null,\"periodDays\":14,\"mealType\":null,\"additionalRequest\":null,\"targetFactorId\":null}");
        responses.add("기록과 건강 정보를 참고해 주세요.");
        int before=requests.get();
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"오늘 무엇을 먹을까?\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("FOOD_CONSULTATION"))
            .andExpect(jsonPath("$.reply").value("기록과 건강 정보를 참고해 주세요."));
        assertEquals(2,requests.get()-before);
        assertTrue(lastRequest.get().contains("두부"));
        responses.add("{\"intent\":\"UNKNOWN_ACTION\"}");
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"다른 질문\"}"))
            .andExpect(status().isBadGateway());
    }
    @Test void healthEditProviderParsesMultipleActions() {
        responses.add("{\"edits\":[{\"action\":\"REMOVE\",\"factorId\":7,\"name\":\"콜레스테롤\",\"type\":\"CONCERN\",\"reportedStatus\":\"REPORTED\",\"originalText\":null},{\"action\":\"ADD\",\"factorId\":null,\"name\":\"통풍\",\"type\":\"DIAGNOSIS\",\"reportedStatus\":\"REPORTED\",\"originalText\":\"통풍 진단\"}]}");
        var edits=chatModel.healthEdits("콜레스테롤은 좋아졌고 통풍 진단을 받았어",List.of(new ChatModel.Factor(7L,"콜레스테롤","CONCERN","REPORTED",true)));
        assertEquals(2,edits.size());
        assertEquals("REMOVE",edits.get(0).action());
        assertEquals("통풍",edits.get(1).name());
    }
}
