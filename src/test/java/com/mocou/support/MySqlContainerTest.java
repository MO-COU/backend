package com.mocou.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class MySqlContainerTest {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mocou")
                    .withUsername("mocou")
                    .withPassword("mocou")
                    .withEnv("TZ", "Asia/Seoul")
                    .withCommand("--log-bin-trust-function-creators=1");

    @Autowired protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeEach
    void resetCouponData() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_used_history");
        jdbcTemplate.update("DELETE FROM coupon_issue_history");
        jdbcTemplate.update("DELETE FROM coupon_issue");
        jdbcTemplate.update("DELETE FROM coupon_stock");
        jdbcTemplate.update("DELETE FROM coupon");
        jdbcTemplate.update("DELETE FROM member");
    }

    protected void insertIssuedCoupon(long issueId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                1001L,
                "member@example.com",
                "테스트 회원",
                "01000000000");
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, discount_rate, open_at, close_at, status) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY, ?)",
                2001L,
                "테스트 쿠폰",
                10,
                "OPEN");
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(coupon_issue_id, coupon_id, member_id, status, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY)",
                issueId,
                2001L,
                1001L);
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history "
                        + "(coupon_issue_id, from_status, to_status, changed_at, idempotency_key) "
                        + "VALUES (?, NULL, 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, ?)",
                issueId,
                "ISSUE:" + issueId);
    }
}
