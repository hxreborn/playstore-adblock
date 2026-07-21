plugins {
    alias(libs.plugins.android.application)
}

val cfgTargetPackage: String = providers.gradleProperty("target.package").get()
val cfgModuleId: String = providers.gradleProperty("module.id").get()
val cfgModuleName: String = providers.gradleProperty("module.name").get()
val cfgModuleAuthor: String = providers.gradleProperty("module.author").get()
val cfgModuleDescription: String = providers.gradleProperty("module.description").get()
val cfgXposedApiMin: Int = providers.gradleProperty("xposed.api.min").get().toInt()
val cfgXposedApiTarget: Int = providers.gradleProperty("xposed.api.target").get().toInt()
val versionNameProvider = providers.gradleProperty("version.name")
val versionCodeProvider = providers.gradleProperty("version.code").map(String::toInt)

android {
    namespace = "eu.hxreborn.gplayadblock"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        applicationId = cfgModuleId
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeProvider.get()
        versionName = versionNameProvider.get()

        buildConfigField("String", "TARGET_PACKAGE", "\"$cfgTargetPackage\"")

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
    implementation(libs.dexkit)
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
        moduleId.set(cfgModuleId)
        moduleName.set(cfgModuleName)
        moduleAuthor.set(cfgModuleAuthor)
        moduleDescription.set(cfgModuleDescription)
        moduleVersionName.set(versionNameProvider)
        moduleVersionCode.set(versionCodeProvider)
        moduleMinApiVersion.set(cfgXposedApiMin)
        moduleTargetApiVersion.set(cfgXposedApiTarget)
        targetPackage.set(cfgTargetPackage)
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
