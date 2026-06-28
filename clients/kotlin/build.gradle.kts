plugins {
    alias(libs.plugins.jorisjonkers.openapi.client)
    `maven-publish`
}

openApiClient {
    useKotlinSpringRestClient()
    specPath.set("client-spec/openapi/knowledge-api.json")
    apiPackage.set("dev.jorisjonkers.knowledge.client.api")
    modelPackage.set("dev.jorisjonkers.knowledge.client.model")
    packageName.set("dev.jorisjonkers.knowledge.client")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["java"])
            groupId = "dev.jorisjonkers"
            artifactId = "knowledge-api-client-kotlin"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/knowledge")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
