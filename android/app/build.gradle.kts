plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número da versão = número da execução no GitHub Actions (cada APK novo instala por cima do anterior).
val versao = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "br.treino.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.treino.app"
        minSdk = 26
        targetSdk = 34
        versionCode = versao
        versionName = "1.$versao"
    }
    // A chave de assinatura fica nos "secrets" do repositório (nunca no código): o Android só aceita
    // instalar uma versão nova por cima se ela for assinada com a mesma chave.
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
}
