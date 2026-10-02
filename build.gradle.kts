plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.codereview"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
    }
    implementation("org.commonmark:commonmark:0.22.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codereview.annotator"
        name = "Code Review Annotator"
        version = project.version.toString()
        description = """
            Add review comments on diffs right from your IDE on a line, a file, or the whole review.
            When you're done, export everything as markdown you can paste into a PR, a chat, or an AI agent.
            Comments show up inline in the editor and persist across restarts.
            <br/><br/>
            Inspired by <a href="https://github.com/agavra/tuicr">tuicr</a>.
        """.trimIndent()
        changeNotes = """
            Initial release.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        vendor {
            name = "Code Review"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}
