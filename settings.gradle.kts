dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("okapi-core")
include("okapi-exposed")
include("okapi-postgres")
include("okapi-mysql")
include("okapi-http")
include("okapi-kafka")
include("okapi-spring-boot")
include("okapi-bom")
include("okapi-integration-tests")
include("okapi-micrometer")

rootProject.name = "okapi"
