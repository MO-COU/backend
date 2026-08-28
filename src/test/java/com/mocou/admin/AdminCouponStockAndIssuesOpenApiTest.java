package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminCouponStockAndIssuesOpenApiTest {

    @Test
    @DisplayName("쿠폰 재고 조회의 Swagger 성공 및 오류 응답을 명세한다")
    void documentsStockOperation() throws Exception {
        Method method = AdminCouponController.class.getMethod("getStock", long.class);

        assertThat(AdminCouponController.class.getAnnotation(Tag.class).name())
                .isEqualTo("관리자 쿠폰·대시보드 API");
        assertThat(method.getAnnotation(Operation.class).summary()).isEqualTo("쿠폰 재고 조회");
        assertThat(responseCodes(method)).containsExactlyInAnyOrder("200", "400", "404", "503");
        assertThat(method.getParameterAnnotations()[0])
                .anySatisfy(
                        annotation -> {
                            assertThat(annotation).isInstanceOf(Parameter.class);
                            assertThat(((Parameter) annotation).description())
                                    .isEqualTo("쿠폰 회차 ID");
                        });
    }

    @Test
    @DisplayName("쿠폰 발급 이력 조회의 Swagger 성공 및 오류 응답을 명세한다")
    void documentsIssuesOperation() throws Exception {
        Method method =
                AdminCouponController.class.getMethod(
                        "getIssues", long.class, int.class, int.class);

        assertThat(AdminCouponController.class.getAnnotation(Tag.class).name())
                .isEqualTo("관리자 쿠폰·대시보드 API");
        assertThat(method.getAnnotation(Operation.class).summary()).isEqualTo("쿠폰 발급 이력 조회");
        assertThat(method.getAnnotation(Operation.class).description())
                .contains("선착순 발급 순번", "순번이 없는 기존 이력은 마지막");
        assertThat(responseCodes(method)).containsExactlyInAnyOrder("200", "400", "404");
        assertThat(Arrays.stream(method.getParameterAnnotations())
                        .flatMap(Arrays::stream)
                        .filter(Parameter.class::isInstance)
                        .map(Parameter.class::cast)
                        .map(Parameter::description))
                .containsExactly(
                        "쿠폰 회차 ID", "페이지 번호(0부터)", "페이지 크기(1~100)");
    }

    private String[] responseCodes(Method method) {
        return Arrays.stream(method.getAnnotation(ApiResponses.class).value())
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode)
                .toArray(String[]::new);
    }
}
