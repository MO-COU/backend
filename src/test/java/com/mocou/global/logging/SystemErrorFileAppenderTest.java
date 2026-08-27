package com.mocou.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

class SystemErrorFileAppenderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path logDirectory;

    @Test
    @DisplayName("시스템 오류 archive 삭제는 백업 스크립트가 담당한다")
    void doesNotConfigureLogbackArchiveDeletion() throws Exception {
        // when
        String configuration = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        // then
        assertThat(configuration)
                .contains("<maxFileSize>10MB</maxFileSize>")
                .doesNotContain("<maxHistory>")
                .doesNotContain("<totalSizeCap>");
    }

    @Test
    @DisplayName("시스템 오류 파일에는 ERROR만 JSON 한 줄로 기록한다")
    void writesOnlyErrorEventsAsJsonLines() throws Exception {
        // given
        LoggerContext loggerContext = new LoggerContext();
        loggerContext.setMDCAdapter(new LogbackMDCAdapter());
        loggerContext.start();
        loggerContext.putProperty("LOG_PATH", logDirectory.toString());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure(Path.of("src/main/resources/logback-spring.xml").toFile());
        Logger logger = loggerContext.getLogger("system-error-file-appender-test");

        try {
            // when
            logger.warn("warn event must not be written to the system error file");
            logger.error(
                    "errorTypes=java.lang.IllegalStateException",
                    new IllegalStateException("member@example.com"));
        } finally {
            loggerContext.stop();
        }

        // then
        Path errorLog = logDirectory.resolve("system-error.log");
        assertThat(errorLog).exists();

        List<String> lines = Files.readAllLines(errorLog);
        assertThat(lines).hasSize(1);

        JsonNode event = OBJECT_MAPPER.readTree(lines.getFirst());
        assertThat(event.path("level").asText()).isEqualTo("ERROR");
        assertThat(event.path("message").asText())
                .isEqualTo("errorTypes=java.lang.IllegalStateException");
        assertThat(event.has("stack_trace")).isFalse();
        assertThat(lines.getFirst()).doesNotContain("member@example.com");
    }
}
