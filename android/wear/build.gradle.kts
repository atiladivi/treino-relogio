plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App do relógio (Wear OS). Mesma numeração e mesma chave de assinatura do app do celular.
val versao = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "br.treino.relogio"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.treino.relogio"
        minSdk = 33 // Wear OS 4 ou mais novo (Galaxy Watch 7 = Wear OS 5)
        targetSdk = 34
        versionCode = versao
        versionName = "1.$versao"
    }
    signingConfigs {
        create("treino") {
            storeFile = file(System.getenv("KS_FILE") ?: "sem-chave.p12")
            storePassword = System.getenv("KS_PASS")
            keyAlias = "treino"
            keyPassword = System.getenv("KS_PASS")
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("treino")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear:wear:1.3.0")          // modo ambiente (pulso baixado)
    implementation("androidx.activity:activity-ktx:1.9.3")
}
