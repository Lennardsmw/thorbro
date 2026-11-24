plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ktor = "3.6.1"

// ---- version (several chats build in parallel worktrees, see CLAUDE.md) ----
// VERSION at the repo root holds the release version; only scripts/release.sh bumps it, on main.
// Builds from any other branch are called "<VERSION>-<branch>+<commit>", so the Thor shows whose build is on it.
// versionCode counts minutes, so every newer build installs over an older one, whatever branch it came from.
fun git(vararg args: String): String = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("")
val baseVersion = rootProject.file("../VERSION").readText().trim()
val gitBranch = git("rev-parse", "--abbrev-ref", "HEAD")
val gitSha = git("rev-parse", "--short", "HEAD")
val gitDirty = git("status", "--porcelain").isNotEmpty()
val onMain = gitBranch == "main" || gitBranch.isEmpty() || gitBranch == "HEAD"
val appVersionName = if (onMain && !gitDirty) baseVersion
    else baseVersion + (if (onMain) "" else "-" + gitBranch.removePrefix("feature/").replace(Regex("[^A-Za-z0-9]+"), "-")) +
        "+" + gitSha + (if (gitDirty) ".dirty" else "")
val appVersionCode = (System.currentTimeMillis() / 60_000L - 28_000_000L).toInt()

android {
    testOptions { unitTests.isReturnDefaultValues = true } // Logs → android.util.Log is a no-op in JVM tests
    namespace = "wtf.lennard.thorbro"
    compileSdk = 37

    defaultConfig {
        applicationId = "wtf.lennard.thorbro"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            // optimized build: much smoother Compose UI than the debug build
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Until there is a real release key, release builds use the debug key (same key as before: updates keep working).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
        }
        // the 3DS room server (scripts/build-room.sh) is an executable: it must be extracted to the lib folder to run
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the real org.json; android.jar only has stubs

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
