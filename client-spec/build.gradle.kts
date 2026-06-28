plugins {
    base
}

val specFile = layout.projectDirectory.file("openapi/knowledge-api.json")

tasks.register("validateCommittedOpenApiSpec") {
    inputs.file(specFile)
    doLast {
        check(specFile.asFile.isFile) { "Missing ${specFile.asFile}" }
    }
}

tasks.named("check") {
    dependsOn("validateCommittedOpenApiSpec")
}
