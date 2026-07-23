import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version local de respaldo (para compilar desde Android Studio sin pasar -P).
// La actualiza bajar_version.bat con el ultimo build publicado en GitHub. No se versiona
// (esta en .gitignore) porque solo es un valor de trabajo de esta maquina.
val localVersionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.dskmusic.kompressall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dskmusic.kompressall"
        minSdk = 30
        targetSdk = 35
        // El Action de release pasa estos valores por linea de comandos (-PversionCode= -PversionName=);
        // en local caen a version.properties (o a 1 / "1.0" si no existe).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: (localVersionProps.getProperty("versionCode")?.toIntOrNull() ?: 1)
        versionName = project.findProperty("versionName") as String?
            ?: localVersionProps.getProperty("versionName") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val envStore = System.getenv("KEYSTORE_FILE")
            val localKeystore = rootProject.file("dskmusic.keystore")
            val localProps = rootProject.file("keystore.properties")
            if (!envStore.isNullOrBlank()) {
                // Usado por el Action de GitHub.
                storeFile = file(envStore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (localKeystore.exists() && localProps.exists()) {
                // Firma local con la misma keystore, para poder instalar builds locales
                // encima de las que vienen de una Release de GitHub sin conflicto de firma.
                val props = Properties().apply { load(FileInputStream(localProps)) }
                storeFile = localKeystore
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInBundle = false
        includeInApk = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
