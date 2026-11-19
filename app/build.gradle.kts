import groovy.json.JsonSlurper
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val galliPickApplicationId = "com.hebi.gallipick"
val cloudTestFirebaseProjectId = "gallipick-dev"
val cloudProductionFirebaseProjectId = "gallipick-production"

// GalliPick ads are opt-in at build time. No live ad requests are possible
// without this app's own AdMob registration and explicit release configuration.
val sampleAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val sampleBannerAdUnitId = "ca-app-pub-3940256099942544/9214589741"
val adsDebugTest = providers.gradleProperty("gallipickAdsTest")
    .map { it.toBooleanStrictOrNull() ?: throw GradleException("gallipickAdsTest must be true or false") }
    .getOrElse(false)
val adsReleaseEnabled = providers.gradleProperty("gallipickAdsEnabled")
    .map { it.toBooleanStrictOrNull() ?: throw GradleException("gallipickAdsEnabled must be true or false") }
    .getOrElse(false)
val adMobAppId = providers.gradleProperty("gallipickAdMobAppId").orNull.orEmpty()
val adMobBannerUnitId = providers.gradleProperty("gallipickAdMobBannerUnitId").orNull.orEmpty()
if (adsReleaseEnabled) {
    require(adMobAppId.matches(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}")) && adMobAppId != sampleAdMobAppId) {
        "A unique GalliPick AdMob app ID is required to enable release ads."
    }
    require(adMobBannerUnitId.matches(Regex("ca-app-pub-[0-9]{16}/[0-9]{10}"))
        && adMobBannerUnitId != sampleBannerAdUnitId) {
        "A unique GalliPick AdMob banner unit ID is required to enable release ads."
    }
}

val cloudConfigResourceNames = listOf(
    "cloud_firebase_app_id", "cloud_firebase_api_key",
    "cloud_firebase_project_id", "cloud_google_web_client_id",
)
val playCloudPilot = providers.gradleProperty("gallipickPlayCloudPilot").map { value ->
    value.toBooleanStrictOrNull() ?: throw GradleException("gallipickPlayCloudPilot must be true or false.")
}.getOrElse(false)
val cloudEnvironment = providers.gradleProperty("gallipickCloudEnvironment").getOrElse("development")
if (cloudEnvironment !in setOf("development", "production")) {
    throw GradleException("gallipickCloudEnvironment must be development or production.")
}
if (cloudEnvironment == "production" && !playCloudPilot) {
    throw GradleException("Production cloud requires an explicitly enabled Play cloud build.")
}
val releaseFirebaseProjectId = if (cloudEnvironment == "production")
    cloudProductionFirebaseProjectId else cloudTestFirebaseProjectId

fun normalizedSha1(value: String?): String? = value?.replace(":", "")?.lowercase()
    ?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }

fun readCloudFirebaseConfig(
    json: String,
    playSigningSha1: String? = null,
    expectedProjectId: String = cloudTestFirebaseProjectId,
    externalAndroidOAuthClientId: String? = null,
    externalWebOAuthClientId: String? = null,
    developmentReference: (() -> String)? = null,
): Map<String, String> {
    fun Map<*, *>.objectField(name: String): Map<*, *> = this[name] as? Map<*, *> ?: emptyMap<Any, Any>()
    fun Map<*, *>.objectArray(name: String): List<Map<*, *>> =
        (this[name] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()

    fun androidClientFor(config: Map<*, *>, projectId: String): Map<*, *> {
        require(config.objectField("project_info")["project_id"] == projectId)
        return config.objectArray("client").single {
            it.objectField("client_info").objectField("android_client_info")["package_name"] == galliPickApplicationId
        }
    }

    fun requireSameProjectOAuth(config: Map<*, *>, androidId: String, webId: String) {
        val projectNumber = config.objectField("project_info")["project_number"] as? String
        require(projectNumber != null && projectNumber.matches(Regex("[0-9]+")))
        val projectClientId = Regex("$projectNumber-[a-zA-Z0-9_-]+\\.apps\\.googleusercontent\\.com")
        require(androidId.matches(projectClientId) && webId.matches(projectClientId) && androidId != webId)
    }

    fun isPlaySigningAndroidClient(oauth: Map<*, *>): Boolean {
        val android = oauth.objectField("android_info")
        return (oauth["client_type"] as? Number)?.toInt() == 1 &&
            android["package_name"] == galliPickApplicationId &&
            normalizedSha1(android["certificate_hash"] as? String) == playSigningSha1
    }

    val config = JsonSlurper().parseText(json) as Map<*, *>
    val client = androidClientFor(config, expectedProjectId)
    val oauthClients = client.objectArray("oauth_client")
    val webClients = oauthClients.filter { (it["client_type"] as? Number)?.toInt() == 3 }
    val production = expectedProjectId == cloudProductionFirebaseProjectId
    val firebaseWebClient = if (production) webClients.single() else webClients.first()
    val firebaseWebId = firebaseWebClient["client_id"] as String
    val directAndroidClients = oauthClients.filter(::isPlaySigningAndroidClient)
    val googleWebId = if (playSigningSha1 != null && directAndroidClients.isEmpty()) {
        require(production)
        val androidId = requireNotNull(externalAndroidOAuthClientId)
        val webId = requireNotNull(externalWebOAuthClientId)
        val referenceConfig = JsonSlurper().parseText(requireNotNull(developmentReference).invoke()) as Map<*, *>
        requireSameProjectOAuth(referenceConfig, androidId, webId)
        val referenceClients = androidClientFor(referenceConfig, cloudTestFirebaseProjectId).objectArray("oauth_client")
        require(referenceClients.any { it["client_id"] == androidId && isPlaySigningAndroidClient(it) })
        require(referenceClients.any { it["client_id"] == webId && (it["client_type"] as? Number)?.toInt() == 3 })
        webId
    } else {
        if (production && playSigningSha1 != null) {
            requireSameProjectOAuth(config, directAndroidClients.single()["client_id"] as String, firebaseWebId)
        }
        firebaseWebId
    }
    val values = listOf(
        client.objectField("client_info")["mobilesdk_app_id"],
        client.objectArray("api_key").first()["current_key"],
        expectedProjectId,
        googleWebId,
    ).map { it as String }
    require(values.all { it.matches(Regex("[a-zA-Z0-9_.:-]+")) })
    require(values.last().endsWith(".apps.googleusercontent.com"))
    return cloudConfigResourceNames.zip(values).toMap()
}

val debugFirebaseConfig = providers.fileContents(layout.projectDirectory.file("src/debug/google-services.json")).asText
    .map { json ->
        try {
            readCloudFirebaseConfig(json)
        } catch (_: Exception) {
            logger.warn("GalliPick cloud sign-in is disabled. Check the debug Firebase Android app configuration and Google sign-in provider.")
            emptyMap()
        }
    }

val playCloudFirebaseConfig = if (playCloudPilot) {
    val json = providers.fileContents(layout.projectDirectory.file("src/release/google-services.json")).asText.orNull
        ?: throw GradleException("The Play cloud pilot requires app/src/release/google-services.json.")
    val signingSha1 = normalizedSha1(providers.gradleProperty("gallipickPlayAppSigningSha1").orNull)
        ?: throw GradleException("The Play cloud pilot requires a valid gallipickPlayAppSigningSha1.")
    try {
        readCloudFirebaseConfig(
            json,
            signingSha1,
            releaseFirebaseProjectId,
            providers.gradleProperty("gallipickPlayExternalAndroidOAuthClientId").orNull,
            providers.gradleProperty("gallipickPlayExternalWebOAuthClientId").orNull,
        ) {
            val reference = File(providers.gradleProperty("gallipickPlayAndroidOAuthReferenceFile").get())
            require(reference.isAbsolute && reference.isFile)
            reference.readText()
        }
    } catch (_: Exception) {
        throw GradleException("Invalid Play cloud Firebase configuration for $cloudEnvironment. Check the Firebase project, Play signing certificate, and Android and web OAuth clients.")
    }
} else emptyMap()

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
        applicationId = galliPickApplicationId
        minSdk = 30
        targetSdk = 36
        versionCode = 32
        versionName = "0.2.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("bool", "gallipick_ads_preview", "false")
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
            resValue("bool", "gallipick_ads_enabled", adsDebugTest.toString())
            resValue("string", "gallipick_admob_app_id", sampleAdMobAppId)
            resValue("string", "gallipick_admob_banner_unit_id", sampleBannerAdUnitId)
            if (!testSigning) signingConfig = signingConfigs.getByName("galliPick")
            resValue("string", "app_name", "GalliPick")
            val firebaseConfig = debugFirebaseConfig.getOrElse(emptyMap())
            cloudConfigResourceNames.forEach { name ->
                resValue("string", name, firebaseConfig[name].orEmpty())
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("galliPick")
            resValue("bool", "gallipick_ads_enabled", adsReleaseEnabled.toString())
            resValue("string", "gallipick_admob_app_id", if (adsReleaseEnabled) adMobAppId else sampleAdMobAppId)
            resValue("string", "gallipick_admob_banner_unit_id", if (adsReleaseEnabled) adMobBannerUnitId else sampleBannerAdUnitId)
        }
        create("playPreparation") {
            initWith(getByName("release"))
            resValue("bool", "gallipick_ads_enabled", "false")
            resValue("string", "gallipick_admob_app_id", sampleAdMobAppId)
            resValue("string", "gallipick_admob_banner_unit_id", sampleBannerAdUnitId)
            signingConfig = null
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        getByName("release") {
            resValue("bool", "gallipick_ads_preview", (playCloudPilot && !adsReleaseEnabled).toString())
            playCloudFirebaseConfig.forEach { (name, value) -> resValue("string", name, value) }
        }
    }
    sourceSets {
        getByName("debug") {
            java.srcDir("src/cloud/java")
            if (!adsDebugTest) manifest.srcFile("src/noAds/AndroidManifest.xml")
        }
        getByName("release") {
            java.srcDir(if (playCloudPilot) "src/cloud/java" else "src/localOnly/java")
            if (!adsReleaseEnabled) manifest.srcFile("src/noAds/AndroidManifest.xml")
        }
        getByName("playPreparation") {
            java.srcDir("src/localOnly/java")
            manifest.srcFile("src/noAds/AndroidManifest.xml")
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
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    val cloudDependencies = listOf(
        "com.google.firebase:firebase-auth:24.0.1",
        "androidx.credentials:credentials:1.3.0",
        "androidx.credentials:credentials-play-services-auth:1.3.0",
        "com.google.android.libraries.identity.googleid:googleid:1.1.1",
    )
    cloudDependencies.forEach { dependency ->
        debugImplementation(dependency)
        if (playCloudPilot) add("releaseImplementation", dependency)
    }
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
