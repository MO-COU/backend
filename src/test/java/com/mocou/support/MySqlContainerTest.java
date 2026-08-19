package com.mocou.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트용 MySQL 컨테이너를 띄우고 접속 정보를 스프링에 연결한다.
 *
 * <p>컨테이너는 JVM당 하나만 만들어 모든 통합 테스트가 공유한다. 데이터 정리와 도메인별 픽스처는 각 테스트가 담당한다. 부모의
 * {@code @BeforeEach}는 자식이 끌 수 없으므로, 여기에 두면 필요 없는 테스트에도 강제된다.
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
}
