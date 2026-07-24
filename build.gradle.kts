import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    signing
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// ─── Layihə məlumatları ───────────────────────────────────────────────────────
group   = "az.mbm"
version = "1.1.55"

// ─── Java versiyası ───────────────────────────────────────────────────────────
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// ─── Repo-lar ─────────────────────────────────────────────────────────────────
repositories {
    mavenCentral()
}

// ─── Asılılıqlar ──────────────────────────────────────────────────────────────
val springBootVersion = "3.2.5"
val jooqVersion       = "3.18.6"
val jakartaVersion    = "3.1.0"

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-jooq:$springBootVersion")
    compileOnly("org.jooq:jooq:$jooqVersion")
    compileOnly("jakarta.persistence:jakarta.persistence-api:$jakartaVersion")
    compileOnly("org.springframework:spring-context:6.1.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Smoke testlər üçün — compileOnly asılılıqlar test classpath-a avtomatik düşmür
    testImplementation("org.jooq:jooq:$jooqVersion")
    testImplementation("jakarta.persistence:jakarta.persistence-api:$jakartaVersion")
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

// ─── İmzalama — köhnə setup: ~/.gradle/secret.pgp + signingPassword ──────────
val signingKeyFile = file("${System.getProperty("user.home")}/.gradle/secret.pgp")
val hasPgpKey = signingKeyFile.exists()
if (hasPgpKey) {
    signing {
        useInMemoryPgpKeys(
            signingKeyFile.readText(),
            project.findProperty("signingPassword") as String?
        )
    }
}

// ─── Maven Central (yeni Central Portal) — Vanniktech plugin ─────────────────
mavenPublishing {
    // Central Portal-a yükləyir və avtomatik release edir.
    // Yalnız staging-də saxlamaq üçün: publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, false)
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // İmzalama — yalnız PGP key mövcud olduqda (local publish-də atlanır).
    if (hasPgpKey) {
        signAllPublications()
    }

    configure(JavaLibrary(
        javadocJar = JavadocJar.Javadoc(),
        sourcesJar = true
    ))

    coordinates("az.mbm", "jooq-sql-generate", version.toString())

    pom {
        name.set("jooq-sql-generate")
        description.set("jOOQ əsaslı dinamik SQL sorğu generatoru kitabxanası")
        url.set("https://github.com/BaxtiyarMammadyarov/jooqsqlgenerate")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("BaxtiyarMammadyarov")
                name.set("Baxtiyar Mammadyarov")
                email.set("baxtiyar.mammadyarov@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/BaxtiyarMammadyarov/jooqsqlgenerate.git")
            developerConnection.set("scm:git:ssh://github.com/BaxtiyarMammadyarov/jooqsqlgenerate.git")
            url.set("https://github.com/BaxtiyarMammadyarov/jooqsqlgenerate")
        }
    }
}
