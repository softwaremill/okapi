plugins {
    `java-platform`
    id("buildsrc.convention.publish")
}

description = "BOM for consistent versioning of Okapi modules"

dependencies {
    constraints {
        api(project(":okapi-core"))
        api(project(":okapi-postgres"))
        api(project(":okapi-mysql"))
        api(project(":okapi-http"))
        api(project(":okapi-kafka"))
        api(project(":okapi-spring-boot"))
    }
}
