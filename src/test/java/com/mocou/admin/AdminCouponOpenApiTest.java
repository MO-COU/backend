package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminCouponOpenApiTest {

    @Test
    @DisplayName("Redis 발급 결과 집계 조회의 Swagger 성공 및 오류 응답을 명세한다")
    void documentsIssueResultCountsOperation() throws Exception {
        Method method = AdminCouponController.class.getMethod("getIssueResultCounts", long.class);
        Tag tag = method.getAnnotation(Tag.class);
        Operation operation = method.getAnnotation(Operation.class);
        ApiResponses responses = method.getAnnotation(ApiResponses.class);

        assertThat(AdminCouponController.class.getAnnotation(Tag.class)).isNull();
        assertThat(tag.name()).isEqualTo("Issue Dashboard");
        assertThat(operation.summary()).isEqualTo("Redis 발급 결과 누적 집계 조회");
        assertThat(Arrays.stream(responses.value())
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode))
                .containsExactlyInAnyOrder("200", "400", "404", "503");
    }
}
