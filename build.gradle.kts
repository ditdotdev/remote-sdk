// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

apply(plugin="com.github.ben-manes.versions")

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.54.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    id("com.github.ben-manes.versions") version("0.54.0")
    jacoco
    `maven-publish`
}

repositories {
    mavenCentral()
    maven {
        name = "dit"
        url = uri("https://dit-maven.s3.amazonaws.com")
    }
}

val ktlint by configurations.creating

dependencies {
	implementation(kotlin("stdlib"))
	implementation("dev.dit:command-executor:1.9.8")
	implementation("org.slf4j:slf4j-api:2.0.18")
	ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0")
	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
	testImplementation("io.mockk:mockk:1.14.11")
}

// Jar configuration
group = "dev.dit"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "dit-maven"
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			groupId = "dev.dit"
			artifactId = "remote-sdk"

			from(components["java"])
		}
	}

    repositories {
        maven {
            name = "dit"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// Treat all warnings as errors
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// Configuration for dependencyUpdates task to ignore release candidates
tasks.withType<DependencyUpdatesTask>().configureEach {
    resolutionStrategy {
        componentSelection {
        	all { selection: ComponentSelection ->
        	    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
            		selection.candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
        	    }
        	    if (rejected) {
            		selection.reject("Release candidate")
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
    mainClass.set("com.pinterest.ktlint.Main")
    args("src/**/*.kt")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fix Kotlin code style deviations"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("-F", "src/**/*.kt")
}

tasks.named("check").get().dependsOn(ktlintTask)

// Test configuration
tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}
