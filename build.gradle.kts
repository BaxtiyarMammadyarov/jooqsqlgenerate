plugins {
    `java-library`
    `maven-publish`
}

// ─── Layihə məlumatları ───────────────────────────────────────────────────────
group   = "az.mbm"
version = "1.0.0"

// ─── Java versiyası ───────────────────────────────────────────────────────────
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()   // *-sources.jar
    withJavadocJar()   // *-javadoc.jar
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// ─── Repo-lar ─────────────────────────────────────────────────────────────────
repositories {
    mavenCentral()
}

// ─── Asılılıqlar ──────────────────────────────────────────────────────────────
// "compileOnly" = compile zamanı lazımdır, amma JAR-a daxil edilmir.
// İstifadəçinin Spring Boot layihəsi bu asılılıqları özü gətirir.
val springBootVersion = "3.2.5"
val jooqVersion       = "3.18.6"
val jakartaVersion    = "3.1.0"

dependencies {

    // jOOQ (DSLContext, Field, Condition, ...)
    compileOnly("org.springframework.boot:spring-boot-starter-jooq:$springBootVersion")
    compileOnly("org.jooq:jooq:$jooqVersion")

    // JPA annotasiyaları (@Table, @Column) — EntityTable üçün
    compileOnly("jakarta.persistence:jakarta.persistence-api:$jakartaVersion")

    // Spring context (isteğe bağlı — QueryFactory @Bean üçün)
    compileOnly("org.springframework:spring-context:6.1.6")

    // ─── Test ────────────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ─── Lokal Maven repo-ya yayımla (~/.m2) ─────────────────────────────────────
// Əmr: ./gradlew publishToMavenLocal
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId    = "az.mbm"
            artifactId = "jooq-sql-generate"
            version    = "1.0.0"

            pom {
                name        = "jooq-sql-generate"
                description = "jOOQ əsaslı dinamik SQL sorğu generatoru kitabxanası"

                // Asılılıqların scope-unu POM-da da "provided" kimi işarələ
                withXml {
                    val deps = asNode().get("dependencies") as groovy.util.NodeList
                    if (deps.isNotEmpty()) {
                        (deps[0] as groovy.util.Node).children().forEach { dep ->
                            val depNode = dep as groovy.util.Node
                            val existing = depNode.get("scope") as groovy.util.NodeList
                            if (existing.isEmpty()) {
                                depNode.appendNode("scope", "provided")
                            }
                        }
                    }
                }
            }
        }
    }
    repositories {
        mavenLocal()
    }
}

// ─── Test konfiqurasiyası ─────────────────────────────────────────────────────
tasks.test {
    useJUnitPlatform()
}

// ─── Javadoc UTF-8 ───────────────────────────────────────────────────────────
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
