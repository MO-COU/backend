package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 중복 실행을 막는 판단만 확인한다.
 *
 * <p>실행기는 대역으로 바꾼다. 여기서 보려는 것은 "지금 시작해도 되는가"라는 판정이지 규칙 실행 결과가 아니다. 진짜 실행기를 쓰면
 * 테스트마다 전 규칙이 돌아 느려지고, 판정과 무관한 이유로 깨진다.
 *
 * <p>유예를 5분으로 못 박는다. 기본값이 바뀌어도 이 테스트의 경계는 흔들리지 않아야 한다.
 */
@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            "mocou.consistency.stale-run-minutes=5"
        })
class VerificationLauncherIntegrationTest extends MySqlContainerTest {

    private static final long NEW_RUN_ID = 42;

    @Autowired private VerificationLauncher launcher;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired private ExecutorService verificationExecutor;

    @MockitoBean private ConsistencyVerifier verifier;

    @BeforeEach
    void clearRunsAndStubStart() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_rule_result");
        jdbcTemplate.update("DELETE FROM verification_run");
        given(verifier.startRun(null)).willReturn(NEW_RUN_ID);
    }

    /**
     * 다음 테스트로 넘어가기 전에 넘긴 실행이 끝나기를 기다린다.
     *
     * <p>{@code launch}는 실행을 다른 스레드로 넘기고 즉시 반환하므로 <b>테스트가 끝나도 그 스레드는 아직 돌고 있을 수
     * 있다.</b> 그대로 두면 다음 테스트에서 대역이 초기화된 뒤 앞 테스트의 호출이 도착해, 호출 횟수를 세는 검증이 환경에 따라
     * 실패한다. 실제로 로컬은 통과하고 CI만 실패했다.
     *
     * <p>실행기가 스레드 하나짜리라 빈 작업을 넣고 그것이 끝나기를 기다리면 <b>앞의 작업이 모두 끝난 것</b>이 보장된다.
     */
    @AfterEach
    void drainAfterEachTest() {
        awaitHandedOffRun();
    }

    /**
     * 넘긴 실행이 끝나기를 기다린다.
     *
     * <p>실행기가 스레드 하나짜리라 빈 작업을 넣고 그것이 끝나기를 기다리면 <b>앞의 작업이 모두 끝난 것</b>이 보장된다.
     */
    private void awaitHandedOffRun() {
        try {
            verificationExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실행기를 기다리다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("넘긴 실행이 끝나지 않았다", e);
        }
    }

    @Test
    @DisplayName("진행 중인 검증이 없으면 시작하고 실행을 다른 스레드로 넘긴다")
    void startsAndHandsOffWhenNothingIsRunning() {
        // when
        long runId = launcher.launch(null);

        // then - 호출한 스레드가 아니라 별도 스레드에서 도므로 끝나기를 기다린 뒤 확인한다
        assertThat(runId).isEqualTo(NEW_RUN_ID);
        awaitHandedOffRun();
        verify(verifier).runAndComplete(NEW_RUN_ID);
    }

    @Test
    @DisplayName("진행 중인 검증이 있으면 거부한다")
    void rejectsWhileAnotherRunIsInProgress() {
        // given
        insertRunStartedMinutesAgo(1, null);

        // when, then - 겹쳐 돌리면 300만 건 스캔이 두 배가 되고 결과 행이 둘로 갈린다
        assertThatThrownBy(() -> launcher.launch(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_ALREADY_RUNNING);

        verify(verifier, never()).startRun(null);
        verify(verifier, never()).runAndComplete(anyLong());
    }

    @Test
    @DisplayName("유예를 넘긴 미완료 실행은 죽은 것으로 보고 새로 시작한다")
    void treatsStaleRunAsDeadAndStartsAnyway() {
        // given - 실행 중 애플리케이션이 죽어 finished_at이 영원히 NULL로 남은 행
        insertRunStartedMinutesAgo(6, null);

        // when
        long runId = launcher.launch(null);

        // then - 이것까지 진행 중으로 보면 그다음부터 검증을 아예 못 하게 된다
        assertThat(runId).isEqualTo(NEW_RUN_ID);
        awaitHandedOffRun();
        verify(verifier).runAndComplete(NEW_RUN_ID);
    }

    @Test
    @DisplayName("이미 끝난 실행은 진행 중으로 보지 않는다")
    void ignoresRunsThatAlreadyFinished() {
        // given - 방금 끝난 실행. started_at만 보면 유예 안이라 finished_at을 함께 봐야 한다
        insertRunStartedMinutesAgo(1, LocalDateTime.now());

        // when
        long runId = launcher.launch(null);

        // then
        assertThat(runId).isEqualTo(NEW_RUN_ID);
    }

    private void insertRunStartedMinutesAgo(int minutes, LocalDateTime finishedAt) {
        namedJdbcTemplate.update(
                """
                INSERT INTO verification_run (issue_run_id, started_at, finished_at)
                VALUES (NULL, :startedAt, :finishedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("startedAt", LocalDateTime.now().minusMinutes(minutes))
                        .addValue("finishedAt", finishedAt));
    }
}
