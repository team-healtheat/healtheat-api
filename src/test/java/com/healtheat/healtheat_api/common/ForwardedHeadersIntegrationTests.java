package com.healtheat.healtheat_api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersIntegrationTests {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    @Test void openApiUsesPublicHttpsAddressFromReverseProxy() throws Exception {
        var headers = new HttpHeaders();
        headers.set("X-Forwarded-Proto", "https");
        headers.set("X-Forwarded-Host", "api.example.test");
        var response = http.exchange("http://localhost:" + port + "/v3/api-docs", HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("https://api.example.test", json.readTree(response.getBody()).path("servers").get(0).path("url").asText());
    }
}
