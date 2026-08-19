package com.mocou.datagen;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(DatagenProperties.class)
class DatagenConfig {

    /**
     * 청크 단위 트랜잭션을 열기 위한 템플릿.
     *
     * <p>{@code @Transactional}을 쓰지 않는 이유는 두 가지다. 생성 메서드 전체에 붙이면 수백만 건이 한 트랜잭션으로 묶이고, 청크
     * 메서드를 따로 빼서 붙여도 같은 클래스 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
     */
    @Bean
    TransactionTemplate datagenTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
