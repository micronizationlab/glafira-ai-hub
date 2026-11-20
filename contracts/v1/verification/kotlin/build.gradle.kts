plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("../../generated/kotlin")
        }
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

sourceSets {
    test {
        resources.srcDir("../../fixtures")
    }
}

tasks.test {
    useJUnit()
}
