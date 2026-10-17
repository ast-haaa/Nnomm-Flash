import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Instant

plugins {
    // Core Spring Boot & Dependency Management plugins
    id("org.springframework.boot") version "3.2.3"
    id("io.spring.dependency-management") version "1.1.4"

    // Kotlin JVM & Spring integration plugins
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.spring") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"

    // Enterprise Quality & Test Coverage
    jacoco
}

group = "com.nnomm"
version = "1.0.0-RELEASE"
description = "Nnomm High-Concurrency Flash Deal Claim Service"

repositories {
    mavenCentral()
}

dependencies {
    // =========================================================================
    // 1. Spring Boot Starters (MVC, JDBC, Validation, Actuator)
    // =========================================================================
    // Embedded Tomcat HTTP engine & Spring Web MVC framework
    implementation("org.springframework.boot:spring-boot-starter-web")

    // HikariCP high-performance connection pool & Spring NamedParameterJdbcTemplate
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Jakarta Bean Validation (@NotNull, @Min, @Size, @Pattern)
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Production-ready health probes (/actuator/health), info, metrics (/actuator/prometheus)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // =========================================================================
    // 2. Interactive API Documentation (OpenAPI 3 / Swagger UI)
    // =========================================================================
    // Generates /swagger-ui/index.html and /v3/api-docs dynamically
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    // =========================================================================
    // 3. Kotlin & Jackson Serialization
    // =========================================================================
    // Kotlin reflection & seamless JSON serialization of data classes
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // ISO-8601 java.time.Instant and OffsetDateTime serialization
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin Coroutines for asynchronous background sweep workers
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.0")

    // =========================================================================
    // 4. Persistence & Database Migrations
    // =========================================================================
    // Official PostgreSQL JDBC Driver (SCRAM-SHA-256 authentication support)
    implementation("org.postgresql:postgresql:42.7.2")

    // Flyway Database Migrations engine
    implementation("org.flywaydb:flyway-core:10.8.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.8.1")

    // =========================================================================
    // 5. Security & Authentication
    // =========================================================================
    // High-performance HMAC-SHA256 JWT generation and validation
    implementation("com.auth0:java-jwt:4.4.0")

    // =========================================================================
    // 6. Testing & Quality Assurance
    // =========================================================================
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    // Testcontainers: Spin up real disposable PostgreSQL 15 Docker containers
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")

    // Embedded PostgreSQL for zero-Docker standalone testing and local devRun mode
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
}

// Java 17 LTS configuration
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xjsr305=strict",                  // Strict nullability for Spring Framework annotations
            "-opt-in=kotlin.RequiresOptIn",     // Enable experimental coroutine primitives
            "-progressive"                       // Progressive compiler mode for future-proof Kotlin
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // Allocate sufficient heap for multi-threaded concurrency and stress testing
    minHeapSize = "512m"
    maxHeapSize = "2048m"
    finalizedBy(tasks.jacocoTestReport)
}

// =============================================================================
// JaCoCo Code Coverage Configuration
// =============================================================================
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/ApplicationKt*",
                    "**/DevServerKt*",
                    "**/config/**",
                    "**/domain/dto/**"
                )
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                minimum = "0.75".toBigDecimal() // 75% instruction coverage threshold
            }
        }
    }
}

// =============================================================================
// Executable BootJar & Packaging
// =============================================================================
tasks.bootJar {
    archiveFileName.set("nnomm-flash-deal-service.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Built-By" to "Nnomm Engineering Team",
                "Build-Jdk" to (System.getProperty("java.version") ?: "unknown"),
                "Build-Time" to Instant.now().toString()
            )
        )
    }
}

tasks.bootRun {
    jvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-Djava.security.egd=file:/dev/./urandom"
    )
}

// =============================================================================
// Zero-Setup Local Development Runner with Embedded PostgreSQL
// =============================================================================
tasks.register<JavaExec>("devRun") {
    group = "application"
    description = "Runs the application with an embedded PostgreSQL database for zero-config local development."
    mainClass.set("com.nnomm.DevServerKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs = listOf(
        "-XX:+UseG1GC",
        "-Dspring.profiles.active=dev",
        "-Djava.security.egd=file:/dev/./urandom"
    )
}
