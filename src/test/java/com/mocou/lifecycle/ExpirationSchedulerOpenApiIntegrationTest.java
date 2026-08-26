package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mocou.support.MySqlContainerTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** springdoc이 Scheduler Control API의 명세를 실제 OpenAPI 문서에 반영하는지 검증한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            "mocou.lifecycle.expiration.scheduler-enabled=false"
        })
class ExpirationSchedulerOpenApiIntegrationTest extends MySqlContainerTest {

    @LocalServerPort private int port;

    @Test
    @DisplayName("Scheduler Control API의 태그와 응답 코드가 OpenAPI 문서에 노출된다")
    void exposesSchedulerControlApiInOpenApiDocument() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:%d/v3/api-docs".formatted(port)))
                        .GET()
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode schedulerControl =
                new ObjectMapper()
                        .readTree(response.body())
                        .path("paths")
                        .path("/internal/lifecycle/expiration-scheduler");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(schedulerControl.path("get").path("tags").get(0).asText()).isEqualTo("Lifecycle");
        assertThat(schedulerControl.path("get").path("summary").asText())
                .isEqualTo("만료 스케줄러 자동 실행 상태 조회");
        assertThat(schedulerControl.path("put").path("summary").asText())
                .isEqualTo("만료 스케줄러 자동 실행 상태 변경");
        assertThat(schedulerControl.path("put").path("responses").has("200")).isTrue();
        assertThat(schedulerControl.path("put").path("responses").has("400")).isTrue();
    }
}
