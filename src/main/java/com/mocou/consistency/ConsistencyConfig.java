package com.mocou.consistency;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConsistencyProperties.class)
class ConsistencyConfig {

    /**
     * 검증을 도는 스레드. 하나만 둔다.
     *
     * <p>검증은 300만 건을 훑으므로 여러 개를 동시에 돌릴 이유가 없고, 돌리면 DB만 그만큼 바빠진다. 스레드가 하나면 겹친 요청이
     * 줄을 서게 되는데, 애초에 {@link VerificationLauncher}가 진행 중인 실행을 막으므로 거기까지 가지 않는다.
     *
     * <p>스레드에 이름을 준다. 로그와 스레드 덤프에서 이 작업이 어느 스레드에서 돌았는지 바로 보인다.
     *
     * <p>데몬 여부는 지정하지 않는다. 종료 시퀀스가 시작되면 JVM은 어차피 데몬이 아닌 스레드도 기다리지 않으므로,
     * {@code setDaemon(true)}를 붙여도 동작이 달라지지 않는다. 종료 도중 끊긴 실행은
     * {@code finished_at = NULL}로 남고, 이는 {@code stale-run-minutes}가 처리한다.
     */
    @Bean
    ExecutorService verificationExecutor() {
        return Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "consistency-verifier"));
    }
}
