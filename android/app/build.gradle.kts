plugins { id("com.android.application") }
val appVersion = Regex("""(?m)^version = "([^"]+)"$""")
    .find(rootProject.file("../Cargo.toml").readText())!!.groupValues[1]
android {
    namespace = "dev.fontlab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.fontlab"
        minSdk = 34
        targetSdk = 35
        versionCode = 7
        versionName = appVersion
        testInstrumentationRunner = "dev.fontlab.LifecycleCheck"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("../../tests/fixtures")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes { debug { isMinifyEnabled = false } }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
