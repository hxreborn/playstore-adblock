plugins {
    alias(libs.plugins.android.application)
}

val targetPackageProvider = providers.gradleProperty("target.package")
val moduleIdProvider = providers.gradleProperty("module.id")
val versionNameProvider = providers.gradleProperty("version.name")
val versionCodeProvider = providers.gradleProperty("version.code").map(String::toInt)

android {
    namespace = "eu.hxreborn.gplayadblock"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = moduleIdProvider.get()
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeProvider.get()
        versionName = versionNameProvider.get()

        buildConfigField("String", "TARGET_PACKAGE", "\"${targetPackageProvider.get()}\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    androidResources {
        localeFilters += "en"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("core") { dimension = "distribution" }
        create("full") {
            dimension = "distribution"
            androidResources.localeFilters += listOf("ru", "zh-rCN")
        }
    }

    signingConfigs {
        create("release") {
            fun secret(name: String): String? =
                providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull

            val storeFilePath = secret("RELEASE_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            } else {
                logger.warn("RELEASE_STORE_FILE not found. Release signing is disabled.")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += "x86_64"
            }
        }

        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources {
            merges += "META-INF/xposed/**"
            excludes +=
                listOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/DEPENDENCIES",
                    "META-INF/*.version",
                    "META-INF/*.kotlin_module",
                    "kotlin/**",
                    "DebugProbesKt.bin",
                )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

val ktlint = configurations.create("ktlint")

dependencies {
    add(ktlint.name, libs.ktlint.cli)
    compileOnly(libs.libxposed.api)
    "fullImplementation"(project(":settings"))
    implementation(libs.dexkit)
    testCompileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.snakeyaml)
}

val hostDexKitDirectory = rootDir.resolve("analysis/tools/dexkit-host")
val releaseFixturesDirectory = rootDir.resolve("analysis/release-fixtures")
val compatibilityMetadata = rootDir.resolve("play-store-compatibility.yaml")

tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(releaseFixturesDirectory) { include("com.android.vending-*/base.apk") },
        hostDexKitDirectory.resolve("libdexkit.so"),
        compatibilityMetadata,
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("java.library.path", hostDexKitDirectory.absolutePath)
    systemProperty("gplayadblock.releaseFixtures", releaseFixturesDirectory.absolutePath)
    systemProperty("gplayadblock.compatibilityMetadata", compatibilityMetadata.absolutePath)
    System.getProperty("gplayadblock.recordOptionalFingerprints")?.let { path ->
        systemProperty("gplayadblock.recordOptionalFingerprints", path)
        outputs.upToDateWhen { false }
    }
    testLogging { showStandardStreams = true }
}

tasks.register<JavaExec>("recordFingerprints") {
    group = "verification"
    description = "Rewrite the recorded target set of every release fixture"
    dependsOn("compileCoreDebugUnitTestSources")
    classpath = files(provider { tasks.named<Test>("testCoreDebugUnitTest").get().classpath })
    mainClass.set("eu.hxreborn.gplayadblock.discovery.RecordFingerprintsKt")
    systemProperty("java.library.path", hostDexKitDirectory.absolutePath)
    systemProperty("gplayadblock.releaseFixtures", releaseFixturesDirectory.absolutePath)
    systemProperty(
        "gplayadblock.fingerprints",
        layout.projectDirectory.dir("src/test/resources/fingerprints").asFile.absolutePath,
    )
}

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = "verification"
        description = "Check Kotlin code style"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("src/**/*.kt")
    }

val ktlintFormat =
    tasks.register<JavaExec>("ktlintFormat") {
        group = "formatting"
        description = "Format Kotlin code"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args("-F", "src/**/*.kt")
    }

abstract class GenerateXposedModuleProp : DefaultTask() {
    @get:Input
    abstract val moduleId: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val moduleAuthor: Property<String>

    @get:Input
    abstract val moduleDescription: Property<String>

    @get:Input
    abstract val moduleVersionName: Property<String>

    @get:Input
    abstract val moduleVersionCode: Property<Int>

    @get:Input
    abstract val moduleMinApiVersion: Property<Int>

    @get:Input
    abstract val moduleTargetApiVersion: Property<Int>

    @get:Input
    abstract val targetPackage: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val moduleProp = outputDir.get().file("META-INF/xposed/module.prop").asFile
        moduleProp.parentFile.mkdirs()
        moduleProp.writeText(
            """
            id=${moduleId.get()}
            name=${moduleName.get()}
            version=${moduleVersionName.get()}
            versionCode=${moduleVersionCode.get()}
            author=${moduleAuthor.get()}
            description=${moduleDescription.get()}
            minApiVersion=${moduleMinApiVersion.get()}
            targetApiVersion=${moduleTargetApiVersion.get()}
            staticScope=true
            exceptionMode=protective
            """.trimIndent() + "\n",
        )
        outputDir.get().file("META-INF/xposed/scope.list").asFile.writeText(
            targetPackage.get() + "\n",
        )
    }
}

val generateXposedModuleProp =
    tasks.register<GenerateXposedModuleProp>("generateXposedModuleProp") {
        moduleId.set(moduleIdProvider)
        moduleName.set(providers.gradleProperty("module.name"))
        moduleAuthor.set(providers.gradleProperty("module.author"))
        moduleDescription.set(providers.gradleProperty("module.description"))
        moduleVersionName.set(versionNameProvider)
        moduleVersionCode.set(versionCodeProvider)
        moduleMinApiVersion.set(providers.gradleProperty("xposed.api.min").map(String::toInt))
        moduleTargetApiVersion.set(providers.gradleProperty("xposed.api.target").map(String::toInt))
        targetPackage.set(targetPackageProvider)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            generateXposedModuleProp,
            GenerateXposedModuleProp::outputDir,
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(ktlintFormat)
}

tasks.named("check").configure {
    dependsOn(ktlintCheck)
}
