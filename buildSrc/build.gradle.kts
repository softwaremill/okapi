plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.ktlintGradlePlugin)
    implementation(libs.vanniktechPublishPlugin)
}
