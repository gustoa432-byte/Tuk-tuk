import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProps = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProps.load(localPropertiesFile.inputStream())
}
val vkAccessToken = localProps.getProperty("VK_ACCESS_TOKEN", "")

/** Release signing from gitignored keystore.properties (see scripts/setup-official-signing.sh). */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseSigning = keystorePropertiesFile.exists().also { exists ->
    if (exists) keystoreProperties.load(keystorePropertiesFile.inputStream())
}

fun releaseCertSha256Hex(): String {
    if (!hasReleaseSigning) return ""
    val storeRel = keystoreProperties.getProperty("storeFile") ?: return ""
    val storePass = keystoreProperties.getProperty("storePassword") ?: return ""
    val alias = keystoreProperties.getProperty("keyAlias") ?: return ""
    val storeFile = rootProject.file(storeRel)
    if (!storeFile.isFile) return ""
    return try {
        // Android uses PKCS12; try PKCS12 first, then default type.
        val ks = try {
            KeyStore.getInstance("PKCS12").also { store ->
                storeFile.inputStream().use { store.load(it, storePass.toCharArray()) }
            }
        } catch (_: Exception) {
            KeyStore.getInstance(KeyStore.getDefaultType()).also { store ->
                storeFile.inputStream().use { store.load(it, storePass.toCharArray()) }
            }
        }
        val cert = ks.getCertificate(alias) ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> "%02x".format(b) }
    } catch (e: Exception) {
        logger.warn("Could not compute release cert SHA-256: ${e.message}")
        ""
    }
}

val expectedReleaseCertSha256 = releaseCertSha256Hex()

android {
    namespace = "com.blink.dtn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blink.dtn"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "0.1.95"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "VK_ACCESS_TOKEN", "\"$vkAccessToken\"")
        buildConfigField("String", "EXPECTED_RELEASE_CERT_SHA256", "\"$expectedReleaseCertSha256\"")
        buildConfigField(
            "boolean",
            "BUILD_IS_RELEASE_SIGNING",
            "${hasReleaseSigning && expectedReleaseCertSha256.isNotEmpty()}"
        )
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                val storeRel = keystoreProperties.getProperty("storeFile")
                    ?: error("keystore.properties missing storeFile")
                storeFile = rootProject.file(storeRel)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: error("keystore.properties missing storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: error("keystore.properties missing keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: error("keystore.properties missing keyPassword")
                check(storeFile!!.isFile) {
                    "Release keystore not found: ${storeFile!!.absolutePath}"
                }
            }
        }
    }

    buildTypes {
        release {
            // R8 minify currently fails on this machine (I/O on merged base.jar).
            // Official = release keystore signature; minify can be re-enabled later.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        // Prefer stable APK entry order for FOSS verification (see docs/REPRODUCIBLE_BUILDS.md).
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8" // Forced rewrite
    }

    // Friendly install names
    applicationVariants.configureEach {
        val vn = versionName
        outputs.configureEach {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = if (name.contains("release", ignoreCase = true)) {
                "tuktuk.v.$vn.apk"
            } else {
                "Tuktuk.apk"
            }
        }
    }
}
gradle.taskGraph.whenReady {
    val wantsReleaseApk = allTasks.any { task ->
        val n = task.name
        n == "assembleRelease" ||
            n == "bundleRelease" ||
            n.endsWith("AssembleRelease") ||
            n == "packageRelease" ||
            n == "signReleaseBundle"
    }
    if (wantsReleaseApk && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is not configured.\n" +
                "Create keystore.properties (gitignored) via:\n" +
                "  ./scripts/setup-official-signing.sh\n" +
                "See docs/OFFICIAL_BUILD.md"
        )
    }
}

dependencies {
    val room_version = "2.6.1"
    val compose_version = "1.6.8"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    
    // Compose
    implementation("androidx.compose.ui:ui:$compose_version")
    implementation("androidx.compose.ui:ui-graphics:$compose_version")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose_version")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:$compose_version")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Room Database
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // Serialization for JSON Packets
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ZXing for QR Code
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.4.1")

    // OkHttp for Cellular Bridge
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$compose_version")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose_version")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$compose_version")
}

tasks.register("decodeKeystore") {
    val keystoreFile = file("debug.keystore")
    val base64File = file("debug.keystore.base64")
    
    onlyIf { !keystoreFile.exists() && base64File.exists() }
    
    doLast {
        val base64 = base64File.readText().replace("\n", "").replace("\r", "")
        keystoreFile.writeBytes(Base64.getDecoder().decode(base64))
    }
}

tasks.whenTaskAdded {
    if (name.contains("validateSigning") || name == "preBuild") {
        dependsOn("decodeKeystore")
    }
}
