import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// --- Versioning -------------------------------------------------------------------------------
// version.properties (repo root) is the single source of truth (see scripts/bump-version.sh).
// Read through `providers` so the configuration cache is invalidated when the file changes.

val appName = "Ripster"

private val semVerRegex = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

private val versionProperties = Properties().apply {
    val file = rootProject.layout.projectDirectory.file("version.properties")
    load(providers.fileContents(file).asText.get().reader())
}

val appVersionName: String = versionProperties.getProperty("VERSION_NAME")?.trim()
    ?.takeIf(semVerRegex::matches)
    ?: error("version.properties: VERSION_NAME must be MAJOR.MINOR.PATCH (e.g. 0.1.0)")

val appVersionCode: Int = versionProperties.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("version.properties: VERSION_CODE must be a positive integer")

// --- Release signing ------------------------------------------------------------------------
// Credentials live in keystore.properties at the repo root (git-ignored; see
// keystore.properties.example). Without that file, release builds are produced unsigned, so a
// fresh clone still builds. Read through `providers` so edits invalidate the config cache.

private val keystoreProperties: Properties? =
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(text.reader()) } }

private fun Properties.required(key: String): String =
    getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("keystore.properties: '$key' is missing or empty")

/** Supports absolute paths, paths relative to the repo root, and a leading `~` for home. */
private fun keystoreFile(path: String): File =
    if (path.startsWith("~")) File(System.getProperty("user.home") + path.removePrefix("~"))
    else rootProject.file(path)

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    // MainActivity starts the shared module's Media3 DownloadService.
    implementation(libs.media3.exoplayer)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "in.geekofia.ripster"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "in.geekofia.ripster"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }
    signingConfigs {
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = keystoreFile(props.required("storeFile"))
                storePassword = props.required("storePassword")
                keyAlias = props.required("keyAlias")
                keyPassword = props.required("keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            // Allows debug and release builds to be installed side by side. The debug launcher
            // label ("Ripster Debug") comes from src/debug/res/values/strings.xml.
            applicationIdSuffix = ".debug"
        }
        release {
            // R8: removes unused code and shortens names; then unused resources are dropped.
            // Libraries ship their own (consumer) keep rules; app rules go in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // Null (unsigned) when keystore.properties is absent.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// --- Named artifacts --------------------------------------------------------------------------
// After `assemble<Variant>` / `bundle<Variant>`, copy the APK / AAB to build/dist/ as
// `Ripster-v<versionName>-<versionCode>-<variant>.<ext>`, e.g. Ripster-v0.1.0-1-release.apk.
// Minified variants also get `<baseName>-mapping.txt` (R8's mapping, to read release stack
// traces). Each copy prints the path it wrote. AGP 9 removed the old `applicationVariants` rename
// hook, so this uses the public artifacts API. The variant name includes the product flavor
// automatically if flavors are added later.

androidComponents {
    onVariants { variant ->
        val taskSuffix = variant.name.replaceFirstChar { it.uppercase() }
        val baseName = "$appName-v$appVersionName-$appVersionCode-${variant.name}"
        val distDir = layout.buildDirectory.dir("dist")

        /** Registers a Copy into build/dist/ that logs the file it wrote. */
        fun registerNamedCopy(task: String, label: String, fileName: String, configure: Copy.() -> Unit) =
            tasks.register<Copy>(task) {
                group = "distribution"
                description = "Copies the ${variant.name} $label to build/dist/$fileName"
                configure()
                into(distDir)
                // Always runs (a quick file copy), so every build prints the path, even when the
                // file didn't change.
                outputs.upToDateWhen { false }
                doLast { logger.lifecycle("$label: ${destinationDir.resolve(fileName)}") }
            }

        // Assumes a single APK per variant (no ABI/density splits configured).
        val copyApk = registerNamedCopy("copy${taskSuffix}NamedApk", "APK", "$baseName.apk") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "$baseName.apk" }
            }
        }
        val copyBundle = registerNamedCopy("copy${taskSuffix}NamedBundle", "AAB", "$baseName.aab") {
            from(variant.artifacts.get(SingleArtifact.BUNDLE)) {
                rename { "$baseName.aab" }
            }
        }
        // AGP produces the mapping file through this artifact (it may write other files, such as
        // mapping.prt, next to it), so don't hard-code outputs/mapping/<variant>/mapping.txt.
        val copyMapping = if (variant.isMinifyEnabled) {
            registerNamedCopy("copy${taskSuffix}NamedMapping", "R8 mapping", "$baseName-mapping.txt") {
                from(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)) {
                    rename { "$baseName-mapping.txt" }
                }
            }
        } else null

        tasks.matching { it.name == "assemble$taskSuffix" }.configureEach {
            finalizedBy(listOfNotNull(copyApk, copyMapping))
        }
        tasks.matching { it.name == "bundle$taskSuffix" }.configureEach {
            finalizedBy(listOfNotNull(copyBundle, copyMapping))
        }
    }
}
