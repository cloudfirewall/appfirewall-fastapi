// Spring Boot autoconfiguration + servlet/reactive filters. Depends on :core.

val springBootVersion = "3.4.0"
val jakartaServletVersion = "6.0.0"
val springWebfluxVersion = "6.2.0"

dependencies {
    api(project(":core"))

    // Spring is compileOnly so a downstream user gets *their* Spring Boot
    // version pulled in via their own starters; ours just compiles against it.
    compileOnly("org.springframework.boot:spring-boot:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure:$springBootVersion")
    // Pulled in so the compiler can resolve @JsonInclude / @JsonProperty
    // annotations referenced on Spring Boot Actuator's Health/Status types.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
    compileOnly("io.micrometer:micrometer-core:1.14.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:$jakartaServletVersion")
    compileOnly("org.springframework:spring-webflux:$springWebfluxVersion")
    compileOnly("io.projectreactor:reactor-core:3.7.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
    testImplementation("io.projectreactor:reactor-test:3.7.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
