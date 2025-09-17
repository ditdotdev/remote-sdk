/*
 * Copyright The Titan Project Contributors.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

apply(plugin="com.github.ben-manes.versions")

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.52.0")
    }
}

plugins {
    kotlin("jvm") version "1.3.60"
    id("com.github.ben-manes.versions") version("0.52.0")
    `maven-publish`
}

repositories {
    mavenCentral()
    jcenter()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "titan"
        url = uri("https://maven.titan-data.io")
    }
}

val ktlint by configurations.creating

dependencies {
    compile(kotlin("stdlib"))
    compile("io.titandata:command-executor:0.1.0")
    compile("org.slf4j:slf4j-api:1.7.29")
    ktlint("com.pinterest:ktlint:0.35.0")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.9.3")
}

// Jar configuration
group = "io.titandata"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "datadatdat-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.titandata"
            artifactId = "remote-sdk"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "titan"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// Treat all warnings as errors
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        allWarningsAsErrors = true
    }
}

// Configuration for dependencyUpdates task to ignore release candidates
tasks.withType<DependencyUpdatesTask>().configureEach {
    resolutionStrategy {
        componentSelection {
        	all {
        	    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
            		candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
        	    }
        	    if (rejected) {
            		reject("Release candidate")
        	    }
        	}
        }
    }
}

// Enable ktlint checks and formatting
val ktlintTask = tasks.register<JavaExec>("ktlint") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Check Kotlin code style"
    classpath = ktlint
    main = "com.pinterest.ktlint.Main"
    args("src/**/*.kt")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fix Kotlin code style deviations"
    classpath = ktlint
    main = "com.pinterest.ktlint.Main"
    args("-F", "src/**/*.kt")
}

tasks.named("check").get().dependsOn(ktlintTask)

// Test configuration
tasks.test {
    useJUnitPlatform()
}
