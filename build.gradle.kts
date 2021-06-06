group = "org.smartremote"
version = "1.0"

plugins {
    java
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

nexusPublishing {
    repositories {
        sonatype()
        create("smart-remote-nexus") {
            nexusUrl.set(uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/"))
            snapshotRepositoryUrl.set(uri("https://oss.sonatype.org/content/repositories/snapshots"))
            username.set("NEXUS_USER")
            password.set("")
        }
    }
}

repositories {
    maven {
        setUrl("http://maven.aliyun.com/nexus/content/groups/public/")
    }
    mavenCentral()
}

tasks.withType<Jar> {
    manifest {
        attributes["Multi-Release"] = true
    }
    from(sourceSets["main"].allSource)
    from(sourceSets["test"].allSource)
    from(tasks["javadoc"])
}

dependencies {
    implementation(group = "org.slf4j", name = "slf4j-api", version = "1.7.30")
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-core", version = "2.11.0")
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.11.0")
    implementation(group = "io.netty", name = "netty-all", version = "4.1.50.Final")
    implementation(group = "com.google.protobuf", name = "protobuf-java", version = "3.15.8")
    implementation(group = "org.projectlombok", name = "lombok", version = "1.18.20")
    implementation(group = "com.google.guava", name = "guava", version = "29.0-jre")
    implementation("org.jetbrains:annotations:19.0.0")

    testImplementation(group = "org.mockito", name = "mockito-core", version = "3.3.3")
    testImplementation(group = "junit", name = "junit", version = "4.12")
}