plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
android {
    namespace = "com.waterwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.waterwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.1"
        val supabaseUrl = project.findProperty("SUPABASE_URL")?.toString() ?: "https://YOUR_PROJECT.supabase.co"
        val supabaseKey = project.findProperty("SUPABASE_ANON_KEY")?.toString() ?: "YOUR_SUPABASE_ANON_KEY"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
