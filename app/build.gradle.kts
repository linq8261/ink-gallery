import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystorePath: String? = localProps.getProperty("signing.storeFile")

// Bugly AppID 刻意不进仓库，避免别人 fork 后往同一个 AppID 上报、污染崩溃数据。
// 本地：写在 local.properties（已在 .gitignore 里）的 bugly.appId。
// CI：由 .github/workflows/release.yml 从仓库变量注入同名环境变量 BUGLY_APP_ID，
//     因为该 workflow 会用 > 覆盖 local.properties，光靠它拿不到 AppID。
val buglyAppId: String = localProps.getProperty("bugly.appId")?.trim()
    ?: providers.environmentVariable("BUGLY_APP_ID").getOrElse("").trim()

android {
    namespace = "com.ink.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ink.gallery"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        buildConfigField("String", "BUGLY_APP_ID", "\"$buglyAppId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("signing.storePassword")
                keyAlias = localProps.getProperty("signing.keyAlias")
                keyPassword = localProps.getProperty("signing.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "SERVER_BASE_URL", "\"https://203.195.213.233/\"")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            buildConfigField("String", "SERVER_BASE_URL", "\"https://203.195.213.233/\"")
            // R8：冷启动的主要成本之一是类加载，dex 缩小后这段能明显缩短。
            // debug 刻意不开：增量编译会变慢，而且启动耗时应该拿 release 包量。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    // 实况图（动态照片）播放：区间直读图片文件尾部内嵌的 MP4，不落临时文件
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 仅用于「检查更新」：查询版本接口，不涉及照片数据
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Bugly 崩溃上报（免费版）：只收集崩溃/ANR，不含业务统计与热更新
    implementation(libs.bugly.crashreport)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
