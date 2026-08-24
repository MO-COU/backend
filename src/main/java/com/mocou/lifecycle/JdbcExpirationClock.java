package com.mocou.lifecycle;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 앱 서버 시간이 아닌 DB 시각으로 만료 기준을 통일한다. */
@Component
public class JdbcExpirationClock implements ExpirationClock {

    private final JdbcTemplate jdbcTemplate;

    public JdbcExpirationClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LocalDateTime now() {
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
    }
}
