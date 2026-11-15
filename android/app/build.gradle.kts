import java.util.Properties

plugins { id("com.android.application") }
val appVersion = Regex("""(?m)^version = "([^"]+)"$""")
    .find(rootProject.file("../Cargo.toml").readText())!!.groupValues[1]
val releaseSigningProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}
fun signingValue(prop: String, env: String): String? =
    releaseSigningProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseStorePath = signingValue("storeFile", "VFONT_KEYSTORE")
val releaseStorePass = signingValue("storePassword", "VFONT_KEYSTORE_PASS")
val releaseKeyAlias = signingValue("keyAlias", "VFONT_KEY_ALIAS")
val releaseKeyPass = signingValue("keyPassword", "VFONT_KEY_PASS")
val copyProjectLicense by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE.md"))
    into(layout.buildDirectory.dir("generated/vfontLicenseAssets"))
    rename { "VFONT_LICENSE.md" }
}
android {
    namespace = "dev.fontlab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.fontlab"
        minSdk = 31
        targetSdk = 35
        versionCode = 101
        versionName = appVersion
        testInstrumentationRunner = "dev.fontlab.LifecycleCheck"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("../../tests/fixtures")
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/vfontLicenseAssets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    signingConfigs {
        val storeFileObj = releaseStorePath?.let { rootProject.file(it) }?.takeIf { it.isFile }
        if (storeFileObj != null && releaseStorePass != null && releaseKeyAlias != null && releaseKeyPass != null) {
            create("release") {
                storeFile = storeFileObj
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig == null) {
                logger.warn("VFont: release keystore not configured; signing release build with debug keystore.")
            }
            signingConfig = releaseConfig ?: signingConfigs.getByName("debug")
        }
    }
}
tasks.named("preBuild") { dependsOn(copyProjectLicense) }
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
