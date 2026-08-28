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
    @DisplayName("OpenAPI 문서의 공통 메타데이터와 오류 응답 형식이 노출된다")
    void exposesOpenApiDocumentation() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:%d/v3/api-docs".formatted(port)))
                        .GET()
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode openApiDocument = new ObjectMapper().readTree(response.body());
        JsonNode schedulerControl =
                openApiDocument.path("paths").path("/api/internal/lifecycle/expiration-scheduler");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(openApiDocument.path("info").path("title").asText()).isEqualTo("MOCOU Coupon API");
        assertThat(openApiDocument.path("info").path("version").asText()).isEqualTo("v1.0");
        assertThat(openApiDocument.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(openApiDocument.path("components").path("schemas").has("ErrorApiResponse"))
                .isTrue();
        assertThat(schedulerControl.path("get").path("tags").get(0).asText())
                .isEqualTo("내부 운영 API");
        assertThat(schedulerControl.path("get").path("summary").asText())
                .isEqualTo("만료 스케줄러 자동 실행 상태 조회");
        assertThat(schedulerControl.path("put").path("summary").asText())
                .isEqualTo("만료 스케줄러 자동 실행 상태 변경");
        assertThat(schedulerControl.path("put").path("responses").has("200")).isTrue();
        assertThat(schedulerControl.path("put").path("responses").has("400")).isTrue();
        assertThat(
                        schedulerControl
                                .path("put")
                                .path("responses")
                                .path("400")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/ErrorApiResponse");
    }
}
