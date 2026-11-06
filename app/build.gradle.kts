import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val testSigning = providers.gradleProperty("gallipickTestSigning").orNull == "true"
val signingPropertiesFile = providers.environmentVariable("GALLIPICK_SIGNING_PROPERTIES").orNull
    ?.let { rootProject.file(it) }
    ?: rootProject.file("signing.properties").takeIf { it.isFile }
    ?: File(System.getProperty("user.home"), ".config/GalliPick/signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val signingStore = signingProperties.getProperty("storeFile")?.let {
    File(it).let { path -> if (path.isAbsolute) path else File(signingPropertiesFile.parentFile, it) }
}
val verifyGalliPickSigning = tasks.register("verifyGalliPickSigning") {
    group = "verification"
    doLast {
        check(signingPropertiesFile.isFile) { "GalliPick signing configuration is missing. Restore the private signing backup or set GALLIPICK_SIGNING_PROPERTIES." }
        fun required(name: String): String = signingProperties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("GalliPick signing configuration is missing $name.")
        required("storeFile")
        val store = signingStore?.takeIf { it.isFile }
            ?: throw GradleException("GalliPick signing keystore is missing. Restore the original key; do not generate a replacement.")
        val keyStore = try {
            KeyStore.getInstance(signingProperties.getProperty("storeType", "PKCS12")).apply {
                store.inputStream().use { load(it, required("storePassword").toCharArray()) }
            }
        } catch (_: Exception) { throw GradleException("GalliPick signing keystore could not be opened. Check the private signing configuration.") }
        val alias = required("keyAlias")
        val available = try { keyStore.isKeyEntry(alias) && keyStore.getKey(alias, required("keyPassword").toCharArray()) != null }
            catch (_: Exception) { false }
        check(available) { "GalliPick signing private key could not be loaded. Check the private signing configuration." }
        val cert = keyStore.getCertificate(alias) ?: throw GradleException("GalliPick signing certificate is missing.")
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = rootProject.file("signing/certificate.sha256").readText().trim().lowercase()
        check(expected.matches(Regex("[0-9a-f]{64}")) && actual == expected) { "GalliPick signing certificate does not match the pinned identity. Restore the original signing backup." }
        logger.lifecycle("GalliPick fixed signing identity verified.")
    }
}
val verifyGalliPickReleaseSigning = tasks.register("verifyGalliPickReleaseSigning") {
    dependsOn(verifyGalliPickSigning)
    doLast { check(!testSigning) { "Ephemeral test signing is allowed only for debug verification, never for release APKs." } }
}

android {
    namespace = "com.hebi.gallipick"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hebi.gallipick"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("galliPick") {
            storeFile = signingStore
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
            storeType = signingProperties.getProperty("storeType", "PKCS12")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            if (!testSigning) signingConfig = signingConfigs.getByName("galliPick")
            resValue("string", "app_name", "GalliPick")
        }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("galliPick") }
        create("playPreparation") {
            initWith(getByName("release"))
            signingConfig = null
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.configureEach {
    if (name in setOf("preReleaseBuild", "validateSigningRelease", "signReleaseBundle")) dependsOn(verifyGalliPickReleaseSigning)
    if (!testSigning && name in setOf("preDebugBuild", "validateSigningDebug", "validateSigningDebugAndroidTest")) dependsOn(verifyGalliPickSigning)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
