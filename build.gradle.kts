plugins {
    `java-library`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

// ─── Layihə məlumatları ───────────────────────────────────────────────────────
group   = "az.mbm"
version = "1.0.10"

// ─── Java versiyası ───────────────────────────────────────────────────────────
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
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
}

// ─── Credentials ─────────────────────────────────────────────────────────────
val sonatypeUsername: String = project.findProperty("sonatypeUsername") as String? ?: ""
val sonatypePassword: String = project.findProperty("sonatypePassword") as String? ?: ""

// ─── İmzalama ─────────────────────────────────────────────────────────────────
val signingPassword: String? = project.findProperty("signingPassword") as String?
val signingKeyFile = file("${System.getProperty("user.home")}/.gradle/secret.pgp")

signing {
    // Yalnız PGP key mövcud olduqda imzalama aktivləşdirilir.
    // Local publish (publishToMavenLocal) zamanı key olmadığına görə avtomatik atlanır.
    if (signingKeyFile.exists()) {
        useInMemoryPgpKeys(signingKeyFile.readText(), signingPassword)
        sign(publishing.publications)
    }
}

// ─── Yayımlama konfiqurasiyası ────────────────────────────────────────────────
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId    = "az.mbm"
            artifactId = "jooq-sql-generate"
            version    = "1.0.10"

            pom {
                name        = "jooq-sql-generate"
                description = "jOOQ əsaslı dinamik SQL sorğu generatoru kitabxanası"
                url         = "https://github.com/BaxtiyarMammadyarov/jooqsqlgenerate"

                licenses {
                    license {
                        name = "MIT License"
                        url  = "https://opensource.org/licenses/MIT"
                    }
                }

                developers {
                    developer {
                        id    = "BaxtiyarMammadyarov"
                        name  = "Baxtiyar Mammadyarov"
                        email = "baxtiyar.mammadyarov@gmail.com"
                    }
                }

                scm {
                    connection          = "scm:git:git://github.com/BaxtiyarMammadyarov/jooqsqlgenerate.git"
                    developerConnection = "scm:git:ssh://github.com/BaxtiyarMammadyarov/jooqsqlgenerate.git"
                    url                 = "https://github.com/BaxtiyarMammadyarov/jooqsqlgenerate"
                }

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
}

// ─── Maven Central (yeni portal) ─────────────────────────────────────────────
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username = sonatypeUsername
            password = sonatypePassword
        }
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
