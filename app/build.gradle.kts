plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voyadio.automotive"
    compileSdk = 36
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.voyadio.automotive"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE", "\"https://voyadio.com\"")
    }
    // Release signing (2026-10-21): only scripts/build-automotive-release.sh passes these, reading the password from
    // the macOS Keychain. The key lives in the git-ignored .signing/ folder; nothing secret is written in this file.
    val releaseKeystore = System.getenv("VOYADIO_CAMP_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storeType = "pkcs12"
            storePassword = System.getenv("VOYADIO_CAMP_KEY_PASSWORD")
            keyAlias = "voyadio-camp"
            keyPassword = System.getenv("VOYADIO_CAMP_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        // No phone signing files or production credentials are read by this project.
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    // Two builds of the same app (2026-10-21): 'pleos' carries the Pleos Connect SDK for Hyundai·Kia·Genesis cars;
    // 'generic' has none of it for every other store (Google, HARMAN Ignite, FORVIA Appning). 42dot's SDK licence
    // (§3.2) allows the SDK only in apps for Pleos Connect. App code meets the SDK only through pleos/PleosBridge.
    flavorDimensions += "store"
    productFlavors {
        create("pleos") { dimension = "store" }
        create("generic") { dimension = "store" }
    }
    buildFeatures { buildConfig = true }
    // Per-app language (Android 13+, Settings › Apps › Voyadio › Language — PO 2026-10-21): the build lists the
    // 50 res/values-* languages for the system; the unqualified values/ folder is English (res/resources.properties).
    androidResources { generateLocaleConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Gleo AI app schema (2026-10-21): Pleos's car assistant reads assets/schema.json from every installed app and calls
// the functions in it by package name — which differs per build (.dev while developing). So src/pleos/gleo/schema.json
// is filled in for each Pleos build; the generic build carries none.
abstract class GleoSchema : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val template: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun write() {
        val schema = template.get().asFile.readText()
            .replace("\${applicationId}", applicationId.get()).replace("\${versionName}", versionName.get())
        output.get().file("schema.json").asFile.writeText(schema)
    }
}
androidComponents {
    onVariants { variant ->
        if (variant.flavorName != "pleos") return@onVariants
        val schema = tasks.register<GleoSchema>("gleoSchema${variant.name.replaceFirstChar { it.uppercase() }}") {
            template.set(layout.projectDirectory.file("src/pleos/gleo/schema.json"))
            applicationId.set(variant.applicationId)
            versionName.set(variant.outputs.single().versionName.orElse(""))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(schema, GleoSchema::output)
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    // Pleos Connect SDK(2026-10-21): 'pleos' 빌드에만. Pleos 차가 아니면 연결이 안 돼 기능이 조용히 꺼진다(pleos/PleosConnect).
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
