package com.mocou.consistency;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import com.mocou.global.logging.SafeExceptionLog;

/**
 * 검증 한 번을 실행하고 결과를 남긴다.
 *
 * <p>읽기와 쓰기를 나눈다. 규칙 쿼리는 읽기 전용 트랜잭션 하나에서 전부 돌아야 하는데, 결과 적재는 INSERT라 그 안에서 할 수 없다.
 * 규칙 결과를 메모리에 모았다가 트랜잭션이 끝난 뒤 저장한다. 위반 상세를 상한까지만 들고 있는 이유가 여기서도 나온다.
 */
@Slf4j
@Component
public class ConsistencyVerifier {

    /**
     * 스냅샷을 트랜잭션을 여는 순간 확정한다.
     *
     * <p>단순히 {@code BEGIN}만 하면 InnoDB는 첫 테이블 읽기까지 읽기 뷰를 만들지 않는다. 그 사이 만료 배치나 발급 동기화가
     * 커밋하면 {@code snapshot_at}으로 기록한 시각과 실제로 본 데이터의 시점이 어긋난다. 스프링의
     * {@code TransactionTemplate}은 이 구문을 발행하지 않으므로 커넥션을 직접 잡아 실행한다.
     */
    private static final String OPEN_SNAPSHOT_SQL = "START TRANSACTION WITH CONSISTENT SNAPSHOT";

    private static final String SNAPSHOT_TIME_SQL = "SELECT CURRENT_TIMESTAMP";

    private final DataSource dataSource;
    private final List<ConsistencyRule> rules;
    private final VerificationRepository repository;
    private final ConsistencyProperties properties;
    private final long expirationDelayMillis;

    public ConsistencyVerifier(
            DataSource dataSource,
            List<ConsistencyRule> rules,
            VerificationRepository repository,
            ConsistencyProperties properties,
            @Value("${mocou.lifecycle.expiration.fixed-delay-ms}") long expirationDelayMillis) {
        this.dataSource = dataSource;
        this.rules = rules;
        this.repository = repository;
        this.properties = properties;
        this.expirationDelayMillis = expirationDelayMillis;
    }

    /**
     * 실행을 시작했다고 기록하고 번호를 돌려준다.
     *
     * <p>규칙을 돌리기 전에 먼저 부르는 이유는 검증이 1~2분 걸리기 때문이다. 그동안 "돌고 있다"는 사실이 DB에 보여야 대시보드가
     * 진행 상황을 알 수 있다. 이 메서드 자체는 INSERT 하나라 즉시 끝나므로 HTTP 응답을 붙잡지 않는다.
     *
     * @param issueRunId 부하 테스트 직후 그 실행을 검증하면 {@code coupon_issue_run.run_id}, 더미데이터 300만
     *     건 전체를 보는 검증이면 {@code null}
     */
    public long startRun(Long issueRunId) {
        return repository.startRun(issueRunId, LocalDateTime.now());
    }

    /**
     * 규칙을 돌리고 결과를 채운다. 오래 걸리므로 호출한 쪽이 별도 스레드에서 실행한다.
     *
     * <p>규칙 하나가 실패하는 경우는 여기서 예외로 올라오지 않는다. {@link #checkAll}이 잡아 {@code FAILED}로
     * 기록하고, 판정이 {@code ERROR}가 되어 정상 경로로 저장된다. 여기서 잡는 것은 스냅샷을 열지 못하는 등 규칙 결과 자체가
     * 없는 경우다. 그때도 실행을 닫아야 {@code finished_at}이 {@code NULL}로 남아 영원히 "돌고 있다"로 보이지 않는다.
     */
    public void runAndComplete(long runId) {
        try {
            SnapshotRun snapshot = runRulesOnSnapshot();
            VerificationResult result =
                    new VerificationResult(
                            snapshot.snapshotAt(), LocalDateTime.now(), snapshot.outcomes());

            repository.completeRun(runId, result);
            log.info(
                    "정합성 검증 완료 (run {}, 판정 {}, 규칙 {}개, 위반 {}건)",
                    runId,
                    result.verdict(),
                    result.outcomes().size(),
                    result.outcomes().stream().mapToLong(RuleOutcome::violationCount).sum());
        } catch (RuntimeException e) {
            log.error(
                    "정합성 검증이 규칙을 돌리지 못하고 끝났다 (run {}, errorTypes={})\n{}",
                    runId,
                    SafeExceptionLog.typeChain(e),
                    SafeExceptionLog.stackFrames(e));
            repository.failRun(runId, LocalDateTime.now());
        }
    }

    private record SnapshotRun(LocalDateTime snapshotAt, List<RuleOutcome> outcomes) {}

    /**
     * 커넥션 하나를 잡아 스냅샷을 열고 규칙을 순회한다.
     *
     * <p>{@link SingleConnectionDataSource}로 감싸는 이유는 규칙들이 풀에서 다른 커넥션을 빌리지 못하게 하기
     * 위해서다. 그러면 애써 연 트랜잭션 밖에서 읽게 되어 규칙마다 다른 시점을 본다.
     */
    private SnapshotRun runRulesOnSnapshot() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(OPEN_SNAPSHOT_SQL);
            }

            NamedParameterJdbcTemplate jdbcTemplate =
                    new NamedParameterJdbcTemplate(
                            new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            LocalDateTime snapshotAt =
                    jdbcTemplate.queryForObject(SNAPSHOT_TIME_SQL, Map.of(), LocalDateTime.class);
            VerificationContext context =
                    new VerificationContext(snapshotAt, graceSeconds(), properties.violationLimit());

            List<RuleOutcome> outcomes = checkAll(jdbcTemplate, context);
            connection.commit();
            return new SnapshotRun(snapshotAt, outcomes);
        } catch (SQLException e) {
            throw new IllegalStateException("검증 스냅샷을 열지 못했다", e);
        }
    }

    /**
     * 규칙 하나가 실패해도 나머지를 계속 돌린다.
     *
     * <p>검증은 읽기 전용이라 되돌릴 변경이 없고 락도 잡지 않는다. 데드락으로 트랜잭션이 통째로 롤백될 상황이 사실상 없으므로, 실패한
     * 규칙만 기록하고 넘어가도 남은 규칙이 같은 스냅샷을 계속 본다. 커넥션 자체가 끊긴 경우라면 뒤 규칙도 모두 실패로 남아 결과가 섞이지
     * 않는다.
     *
     * <p>무거운 규칙을 지나 마지막에 터졌을 때 앞서 얻은 결과를 버리지 않는다는 이점도 있다.
     */
    private List<RuleOutcome> checkAll(
            NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        List<RuleOutcome> outcomes = new ArrayList<>(rules.size());
        for (ConsistencyRule rule : rules) {
            try {
                outcomes.add(rule.check(jdbcTemplate, context));
            } catch (RuntimeException e) {
                log.error(
                        "검증 규칙 {} 실행 실패 (errorTypes={})\n{}",
                        rule.rule(),
                        SafeExceptionLog.typeChain(e),
                        SafeExceptionLog.stackFrames(e));
                outcomes.add(RuleOutcome.failed(rule.rule(), describe(e)));
            }
        }
        return outcomes;
    }

    /** 예외 메시지가 비어 있는 구현이 있어 클래스 이름을 함께 남긴다. 사유가 없으면 {@code RuleOutcome}이 생성을 거부한다. */
    private String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : "%s: %s".formatted(e.getClass().getSimpleName(), message);
    }

    /** 유예는 만료 배치 주기에서 파생한다. B2 클래스를 import하지 않고 설정값만 읽어 패키지 결합을 만들지 않는다. */
    private long graceSeconds() {
        return expirationDelayMillis * properties.graceMultiplier() / 1000;
    }
}
