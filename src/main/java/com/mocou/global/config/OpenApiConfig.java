package com.mocou.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger 문서의 공통 메타데이터와 오류 응답 형식을 정의한다. */
@Configuration
public class OpenApiConfig {

    private static final String ERROR_API_RESPONSE_SCHEMA = "ErrorApiResponse";
    private static final String APPLICATION_JSON = "application/json";

    @Bean
    public OpenAPI mocouOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("MOCOU Coupon API")
                                .version("v1.0")
                                .description("선착순 쿠폰 발급과 운영을 위한 API 문서입니다."))
                .servers(java.util.List.of(new Server().url("/").description("현재 서버")))
                .components(
                        new Components()
                                .addSchemas(ERROR_API_RESPONSE_SCHEMA, errorApiResponseSchema()));
    }

    @Bean
    public OpenApiCustomizer errorApiResponseCustomizer() {
        return openApi -> {
            openApi
                    .getComponents()
                    .addSchemas(ERROR_API_RESPONSE_SCHEMA, errorApiResponseSchema());
            openApi
                    .getPaths()
                    .values()
                    .forEach(
                            pathItem ->
                                    pathItem
                                            .readOperations()
                                            .forEach(
                                                    operation ->
                                                            operation
                                                                    .getResponses()
                                                                    .forEach(
                                                                            (statusCode, response) -> {
                                                                                if (isErrorStatus(statusCode)) {
                                                                                    response.setContent(
                                                                                            new Content()
                                                                                                    .addMediaType(
                                                                                                            APPLICATION_JSON,
                                                                                                            new MediaType()
                                                                                                                    .schema(errorApiResponseReference())
                                                                                                                    .example(errorApiResponseExample())));
                                                                                }
                                                                            })));
        };
    }

    private Schema<?> errorApiResponseSchema() {
        return new ObjectSchema()
                .description("실패한 API 요청의 공통 응답 형식")
                .addProperty("success", new BooleanSchema().example(false))
                .addProperty("data", new ObjectSchema().nullable(true))
                .addProperty(
                        "error",
                        new ObjectSchema()
                                .addProperty("code", new StringSchema().example("INVALID_INPUT"))
                                .addProperty("message", new StringSchema().example("잘못된 요청입니다")))
                .addProperty("traceId", new StringSchema().example("c0ffee00-0000-4000-8000-000000000000"))
                .addProperty(
                        "timestamp",
                        new StringSchema()
                                .format("date-time")
                                .example("2026-08-28T10:30:00+09:00"));
    }

    private Schema<?> errorApiResponseReference() {
        return new Schema<>().$ref("#/components/schemas/" + ERROR_API_RESPONSE_SCHEMA);
    }

    private Map<String, Object> errorApiResponseExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("success", false);
        example.put("data", null);
        example.put("error", Map.of("code", "INVALID_INPUT", "message", "잘못된 요청입니다"));
        example.put("traceId", "c0ffee00-0000-4000-8000-000000000000");
        example.put("timestamp", "2026-08-28T10:30:00+09:00");
        return example;
    }

    private boolean isErrorStatus(String statusCode) {
        return statusCode.startsWith("4") || statusCode.startsWith("5");
    }
}
