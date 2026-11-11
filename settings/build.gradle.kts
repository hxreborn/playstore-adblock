plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "eu.hxreborn.gplayadblock.settings"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

val ktlint = configurations.create("ktlint")

dependencies {
    add(ktlint.name, libs.ktlint.cli)
}

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = "verification"
        description = "Check Kotlin code style"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("src/**/*.kt", "*.kts")
    }

val ktlintFormat =
    tasks.register<JavaExec>("ktlintFormat") {
        group = "formatting"
        description = "Format Kotlin code"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("-F", "src/**/*.kt", "*.kts")
    }

tasks.named("check").configure {
    dependsOn(ktlintCheck)
}
