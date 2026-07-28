package buildsrc.convention

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()
    // Only sign when a key is actually configured (CI's release job sets this via
    // ORG_GRADLE_PROJECT_signingInMemoryKey). Signing unconditionally breaks `publishToMavenLocal`
    // for local development/testing, since no GPG key is available outside the release job.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "Transactional outbox pattern for Kotlin/JVM" })
        url.set("https://github.com/softwaremill/okapi")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("softwaremill")
                name.set("SoftwareMill")
                url.set("https://softwaremill.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/softwaremill/okapi.git")
            developerConnection.set("scm:git:ssh://github.com/softwaremill/okapi.git")
            url.set("https://github.com/softwaremill/okapi")
        }
    }
}
