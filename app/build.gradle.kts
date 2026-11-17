plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Room schema history is committed (app/schemas) so future migrations can be written and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "dev.avijit.orbittuner"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.avijit.orbittuner"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        // The OrbitTuner service every install talks to (debug builds may override it in Settings > Developer).
        buildConfigField("String", "SERVICE_HOST", "\"tuner.orbitapps.space\"")
        // Google sign-in: the OAuth *web* client ID the service accepts (settings google_client_ids). Set the Gradle
        // property ORBITTUNER_GOOGLE_WEB_CLIENT_ID (e.g. ~/.gradle/gradle.properties); empty hides the button.
        val googleClientId = (findProperty("ORBITTUNER_GOOGLE_WEB_CLIENT_ID") as String?).orEmpty().trim()
        require(googleClientId.matches(Regex("[A-Za-z0-9._-]*"))) { "ORBITTUNER_GOOGLE_WEB_CLIENT_ID has unexpected characters" }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClientId\"")
    }
    // Release signing is optional: only configured when all four Gradle properties are present
    // (e.g. in ~/.gradle/gradle.properties, never in the repository). ORBITTUNER_RELEASE_* wins; otherwise the
    // shared Orbit apps key (ORBIT_RELEASE_*) is used. Without either, the release build stays unsigned.
    val signingKeys = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val releaseProps = listOf("ORBITTUNER_RELEASE_", "ORBIT_RELEASE_")
        .map { prefix -> signingKeys.associateWith { findProperty(prefix + it) as String? } }
        .firstOrNull { props -> props.values.all { it != null } }
    signingConfigs {
        if (releaseProps != null) create("release") {
            storeFile = file(releaseProps.getValue("STORE_FILE")!!)
            storePassword = releaseProps.getValue("STORE_PASSWORD")
            keyAlias = releaseProps.getValue("KEY_ALIAS")
            keyPassword = releaseProps.getValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // ChordPro fixtures are shared with the Cloudflare Worker service (added by a later plan).
    sourceSets["test"].resources.srcDir("../service/test/fixtures")
    // Room schema history for MigrationTestHelper (Robolectric reads the variant's assets; debug builds only).
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.0.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
}

// Compose UI tests need ui-test-manifest (debug-only); the debug variant covers the same sources.
tasks.configureEach { if (name == "testReleaseUnitTest") enabled = false }
