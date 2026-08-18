plugins {
	java
	id("org.springframework.boot") version "4.1.0-RC1"
	id("io.spring.dependency-management") version "1.1.6"
}

group = "com.mocou"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
	maven { url = uri("https://repo.spring.io/milestone") } // Spring Boot 4.1.0-RC1 관련 의존성 (GA 아님)
}

val querydslVersion = "5.1.0"

dependencies {
	// Web / Core
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Persistence
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	runtimeOnly("com.mysql:mysql-connector-j")

	// QueryDSL
	implementation("com.querydsl:querydsl-jpa:$querydslVersion:jakarta")
	annotationProcessor("com.querydsl:querydsl-apt:$querydslVersion:jakarta")
	annotationProcessor("jakarta.annotation:jakarta.annotation-api")
	annotationProcessor("jakarta.persistence:jakarta.persistence-api")

	// Redis (Lettuce, Spring Boot 기본 클라이언트)
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	// Batch
	implementation("org.springframework.boot:spring-boot-starter-batch")

	// Migration (Boot 4.x부터 flyway-core 단독이 아니라 이 스타터로 분리됨)
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-mysql")

	// Logging (JSON)
	implementation("net.logstash.logback:logstash-logback-encoder:8.0")

	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// Test
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.batch:spring-batch-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")
	testImplementation("com.redis:testcontainers-redis:2.2.2")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

sourceSets {
	main {
		java {
			srcDirs("src/main/java", "build/generated/querydsl")
		}
	}
}

tasks.named<JavaCompile>("compileJava") {
	options.generatedSourceOutputDirectory.set(file("build/generated/querydsl"))
}
