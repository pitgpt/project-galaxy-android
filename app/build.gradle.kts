plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val gitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
val galaxyApiBaseUrl = providers.gradleProperty("galaxyApiBaseUrl").orElse(providers.environmentVariable("GALAXY_API_BASE_URL")).getOrElse("https://api.galaxy.invalid")
val supabaseUrl = providers.gradleProperty("supabaseUrl").orElse(providers.environmentVariable("SUPABASE_URL")).getOrElse("")
val supabasePublishableKey = providers.gradleProperty("supabasePublishableKey").orElse(providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY")).getOrElse("")
fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.pitgpt.galaxy"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pitgpt.galaxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        buildConfigField("String", "GIT_SHA", buildConfigString(gitSha))
        buildConfigField("String", "GALAXY_API_BASE_URL", buildConfigString(galaxyApiBaseUrl))
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabasePublishableKey))
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.6"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-android:3.3.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
