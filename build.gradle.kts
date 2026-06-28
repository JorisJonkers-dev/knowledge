plugins {
    base
}

group = "dev.jorisjonkers"
version =
    providers
        .gradleProperty("artifactVersion")
        .orElse(providers.environmentVariable("GITHUB_REF_NAME").map { it.removePrefix("v") })
        .orElse("0.1.0-SNAPSHOT")
        .get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}
