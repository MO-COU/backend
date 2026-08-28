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
    @DisplayName("알림 처리 현황 조회의 Swagger 성공 및 오류 응답을 명세한다")
    void documentsNotificationCountsOperation() throws Exception {
        Method method = AdminCouponController.class.getMethod("getNotificationCounts", long.class);
        Tag tag = AdminCouponController.class.getAnnotation(Tag.class);
        Operation operation = method.getAnnotation(Operation.class);
        ApiResponses responses = method.getAnnotation(ApiResponses.class);

        assertThat(tag.name()).isEqualTo("관리자 쿠폰·대시보드 API");
        assertThat(tag.description()).isEqualTo("관리자 쿠폰 회차와 대시보드 조회 API");
        assertThat(operation.summary()).isEqualTo("알림 처리 현황 조회");
        assertThat(operation.description())
                .isEqualTo("회차별 발급 성공 알림의 전체·완료·대기·실패 건수를 조회합니다.");
        assertThat(Arrays.stream(responses.value())
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode))
                .containsExactlyInAnyOrder("200", "400", "404");
    }

    @Test
    @DisplayName("발급 결과와 DB 적재 진행 조회의 Swagger 성공 및 오류 응답을 명세한다")
    void documentsIssueResultCountsOperation() throws Exception {
        Method method = AdminCouponController.class.getMethod("getIssueResultCounts", long.class);
        Tag tag = AdminCouponController.class.getAnnotation(Tag.class);
        Operation operation = method.getAnnotation(Operation.class);
        ApiResponses responses = method.getAnnotation(ApiResponses.class);

        assertThat(tag.name()).isEqualTo("관리자 쿠폰·대시보드 API");
        assertThat(tag.description()).isEqualTo("관리자 쿠폰 회차와 대시보드 조회 API");
        assertThat(operation.summary()).isEqualTo("발급 결과와 DB 적재 진행 조회");
        assertThat(operation.description())
                .isEqualTo("Redis 발급 결과 누적값과 DB 적재 진행을 함께 조회합니다.");
        assertThat(Arrays.stream(responses.value())
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode))
                .containsExactlyInAnyOrder("200", "400", "404", "503");
    }

    @Test
    @DisplayName("회차 목록 조회의 Swagger 성공 응답을 명세한다")
    void documentsCouponListOperation() throws Exception {
        Method method = AdminCouponController.class.getMethod("getCoupons");
        Tag tag = AdminCouponController.class.getAnnotation(Tag.class);
        Operation operation = method.getAnnotation(Operation.class);
        ApiResponses responses = method.getAnnotation(ApiResponses.class);

        assertThat(tag.name()).isEqualTo("관리자 쿠폰·대시보드 API");
        assertThat(operation.summary()).isEqualTo("회차 목록 조회");
        assertThat(Arrays.stream(responses.value())
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode))
                .containsExactly("200");
    }
}
