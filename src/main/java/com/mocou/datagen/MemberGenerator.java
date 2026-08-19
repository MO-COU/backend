package com.mocou.datagen;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가상 회원 데이터를 생성해 적재한다.
 *
 * <p>모든 필드를 회원 번호에서 결정론적으로 계산한다. 난수 생성기를 공유하지 않으므로 적재를 여러 스레드로 나눠도 같은 회원 번호는 항상 같은 값을
 * 갖는다. 난수 생성기 하나를 공유하면 스레드 스케줄링에 따라 값의 배분이 달라져 재현성이 깨진다.
 *
 * <p>여기서 만드는 이름·연락처·이메일은 실제 개인정보와 무관한 합성값이다. 형식은 C팀 마스킹 규칙에 맞춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MemberGenerator {

    private static final String INSERT_SQL =
            "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)";

    private static final String[] SURNAMES = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"};
    private static final String[] GIVEN_FIRST = {"서", "지", "민", "현", "예", "준", "하", "도", "시", "유"};
    private static final String[] GIVEN_SECOND = {"준", "우", "연", "아", "은", "호", "진", "빈", "원", "율"};

    /** 회원 번호를 자릿수별로 흩뜨리기 위한 곱수. 결정론적이면서 연속된 번호가 비슷한 값으로 몰리지 않게 한다. */
    private static final long SCRAMBLE = 2_654_435_761L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DatagenProperties properties;

    /**
     * 회원 번호 1번부터 {@code memberCount}번까지를 청크 단위로 적재한다.
     *
     * <p>청크 하나가 트랜잭션 하나다. 전체를 한 트랜잭션으로 묶으면 언두 로그가 계속 쌓이고 실패 시 롤백이 오래 걸리며, 트랜잭션을 아예 걸지
     * 않으면 autocommit이라 행마다 커밋되어 커밋 횟수만큼 디스크 동기화가 발생한다.
     *
     * @return 적재한 회원 수
     */
    int generate(LocalDateTime baseTime) {
        int total = properties.memberCount();
        int chunkSize = properties.chunkSize();
        LocalDateTime startedAt = LocalDateTime.now();

        for (int from = 1; from <= total; from += chunkSize) {
            int to = Math.min(from + chunkSize - 1, total);
            List<Object[]> rows = rows(from, to, baseTime);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(INSERT_SQL, rows));
            log.debug("회원 적재 진행 {}/{}", to, total);
        }

        Duration elapsed = Duration.between(startedAt, LocalDateTime.now());
        log.info("회원 {}건 적재 완료 ({}ms, 청크 {}건)", total, elapsed.toMillis(), chunkSize);
        return total;
    }

    private List<Object[]> rows(int from, int to, LocalDateTime baseTime) {
        List<Object[]> rows = new ArrayList<>(to - from + 1);
        for (int index = from; index <= to; index++) {
            rows.add(
                    new Object[] {
                        (long) index,
                        email(index),
                        name(index),
                        phone(index),
                        Timestamp.valueOf(createdAt(index, baseTime))
                    });
        }
        return rows;
    }

    static String email(int index) {
        return String.format("user%08d@mocou.test", index);
    }

    static String name(int index) {
        long scrambled = scramble(index);
        return SURNAMES[(int) (scrambled % SURNAMES.length)]
                + GIVEN_FIRST[(int) ((scrambled / 10) % GIVEN_FIRST.length)]
                + GIVEN_SECOND[(int) ((scrambled / 100) % GIVEN_SECOND.length)];
    }

    static String phone(int index) {
        long scrambled = scramble(index);
        return String.format("010-%04d-%04d", (scrambled / 10_000) % 10_000, scrambled % 10_000);
    }

    static LocalDateTime createdAt(int index, LocalDateTime baseTime) {
        return baseTime.minusDays(90 - (index % 90));
    }

    private static long scramble(int index) {
        return Math.abs(index * SCRAMBLE) % 100_000_000L;
    }
}
