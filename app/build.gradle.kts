/* نسختين من نفس المشروع وبنفس أمر البناء:
   emp   = تطبيق الموظفين (بوابة الموظفين)  ·  admin = تطبيق المحاسبي (الإدارة)
   رقم الإصدار لكل نسخة هنا ↓ — زوّد الرقم اللي تبي تحدّثه */
val empVersionCode = 6
val empVersion = "1.1.3"
val adminVersionCode = 2
val adminVersion = "1.0.1"

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sa.bluebrand.emb.mobile"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }

    flavorDimensions += "app"
    productFlavors {
        create("emp") {
            dimension = "app"
            applicationId = "sa.bluebrand.emb.mobile"
            versionCode = empVersionCode
            versionName = empVersion
            buildConfigField("String", "HOME_URL", "\"https://bluebrand-emp.web.app/\"")
            buildConfigField("String", "UA_TAG", "\"BlueBrandEmpAndroid\"")
            buildConfigField("String", "OLD_PKGS", "\"sa.bluebrand.portal\"")
            buildConfigField("String", "KEEP_PKGS", "\"sa.bluebrand.cc,sa.bluebrand.mobile\"")
            buildConfigField("boolean", "OLD_BY_NAME", "true")
        }
        create("admin") {
            dimension = "app"
            applicationId = "sa.bluebrand.mobile"
            versionCode = adminVersionCode
            versionName = adminVersion
            buildConfigField("String", "HOME_URL", "\"https://bluebrand-cc.web.app/\"")
            buildConfigField("String", "UA_TAG", "\"BlueBrandAdminAndroid\"")
            buildConfigField("String", "OLD_PKGS", "\"sa.bluebrand.cc\"")
            buildConfigField("String", "KEEP_PKGS", "\"sa.bluebrand.emb.mobile,sa.bluebrand.portal\"")
            buildConfigField("boolean", "OLD_BY_NAME", "false")
        }
    }

    /* توقيع ثابت من أسرار GitHub — عشان كل تحديث جاي ينثبت فوق القديم مباشرة */
    signingConfigs {
        create("bb") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("bb")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.0")
    implementation("androidx.core:core:1.15.0")
}
