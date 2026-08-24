package com.mocou.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트용 MySQL 컨테이너를 띄우고 접속 정보를 스프링에 연결한다.
 *
 * <p>컨테이너는 JVM당 하나만 만들어 모든 통합 테스트가 공유한다. 그래서 앞선 테스트가 남긴 행이 다음 테스트에 그대로 넘어간다. 특정 테이블만
 * 비우면 FK로 연결된 부모 행을 지우지 못하므로, 도메인 그래프 전체를 여기서 한 번에 정리한다.
 *
 * <p>도메인 전용 픽스처와 실패 재현용 트리거처럼 일부 테스트에만 필요한 것은 각 도메인 테스트가 담당한다.
 *
 * <p>JDBC URL 파라미터는 {@code application-local.yml}과 맞춘다. 드라이버 옵션이 다르면 배치 전송 방식이나 시각 해석이 달라져
 * 테스트가 운영 조건을 재현하지 못한다. {@code @DynamicPropertySource}가 가장 높은 우선순위를 가지므로 자식 테스트에서는 덮어쓸 수
 * 없고, 여기서만 지정할 수 있다.
 */
public abstract class MySqlContainerTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mocou")
                    .withUsername("mocou")
                    .withPassword("mocou")
                    .withEnv("TZ", "Asia/Seoul")
                    .withCommand("--log-bin-trust-function-creators=1")
                    .withUrlParam("characterEncoding", "UTF-8")
                    .withUrlParam("serverTimezone", "Asia/Seoul")
                    .withUrlParam("rewriteBatchedStatements", "true");

    static {
        MYSQL.start();
    }

    @Autowired protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    /**
     * 자식 테이블부터 지워야 FK 제약에 걸리지 않는다.
     *
     * <p>{@code coupon}을 참조하는 테이블은 {@code coupon_issue}·{@code coupon_stock}·
     * {@code coupon_issue_run}·{@code notification}이고, {@code member}를 참조하는 것은
     * {@code coupon_issue}·{@code notification}이다. 하나라도 빠지면 그 행을 남긴 테스트 다음에 오는
     * 테스트가 {@code DELETE FROM coupon}에서 막힌다.
     */
    @BeforeEach
    void resetCouponData() {
        jdbcTemplate.update("DELETE FROM coupon_issue_history");
        jdbcTemplate.update("DELETE FROM coupon_issue");
        jdbcTemplate.update("DELETE FROM coupon_issue_run");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM issue_failure_log");
        jdbcTemplate.update("DELETE FROM coupon_stock");
        jdbcTemplate.update("DELETE FROM coupon");
        jdbcTemplate.update("DELETE FROM member");
    }
}
