plugins {
    `java-platform`
}

dependencies {
    constraints {
        api(project(":okapi-core"))
        api(project(":okapi-postgres"))
        api(project(":okapi-http"))
        api(project(":okapi-kafka"))
        api(project(":okapi-spring-boot"))
    }
}
