plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)
    compileOnly(libs.kafkaClients)

    testImplementation(libs.kafkaClients)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
}

// CI version override: ./gradlew :okapi-kafka:test -PkafkaVersion=4.0.2
val kafkaVersion: String? by project

if (kafkaVersion != null) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.apache.kafka") {
                useVersion(kafkaVersion!!)
            }
        }
    }
}
