plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ktor = "3.6.1"

android {
    namespace = "wtf.lennard.thorbro"
    compileSdk = 37

    defaultConfig {
        applicationId = "wtf.lennard.thorbro"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "0.10.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Until there is a real release key, release builds use the debug key.
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
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
}
