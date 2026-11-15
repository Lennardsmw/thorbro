plugins {
    id("com.android.application")
}

val ktor = "3.6.1"

android {
    namespace = "wtf.lennard.thorbro"
    compileSdk = 36

    defaultConfig {
        applicationId = "wtf.lennard.thorbro"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Until there is a real release key, release builds use the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

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
}
