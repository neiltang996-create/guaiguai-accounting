import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 发布签名：用 keystore/keystore.properties 里的固定密钥。
// 固定签名很重要 —— 换了签名就无法覆盖安装，只能卸载重装（会丢本地数据）。
// 注意：这里不能写 `java.util.Properties()` —— Kotlin DSL 脚本里 `java` 会被解析成
// JavaPluginExtension（java 扩展），把包名遮住，所以靠顶部的 import 直接用 Properties()。
val keystorePropsFile = file(System.getenv("FL_SIGNING_PROPERTIES") ?: rootProject.file("keystore/keystore.properties").path)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

// Local configuration is optional, ignored by Git, and never required by CI.
val localConfig = Properties().apply {
    val source = rootProject.file("config.local.properties")
    if (source.exists()) source.reader(Charsets.UTF_8).use { load(it) }
}
fun setting(key: String, fallback: String = ""): String = localConfig.getProperty(key, fallback)
fun literal(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.family.ledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.guaiguai.accounting"
        buildConfigField("boolean", "DEMO_MODE", "false")
        buildConfigField("String", "DEFAULT_SYNC_TOKEN", literal(setting("sync.token")))
        buildConfigField("String", "DEFAULT_LAN_URL", literal(setting("sync.lanUrl")))
        buildConfigField("String", "DEFAULT_WAN_URL", literal(setting("sync.wanUrl")))
        buildConfigField("String", "PERSON_A_NAME", literal(setting("person.a.name", "用户 A")))
        buildConfigField("String", "PERSON_B_NAME", literal(setting("person.b.name", "用户 B")))
        buildConfigField("String", "HOUSEHOLD_ID", literal(setting("household.id", "DEMOHOME")))
        buildConfigField("String", "CHARGING_MERCHANT", literal(setting("merchant.charging", "示例充电服务有限公司")))
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 独立开发包，设备测试和正式数据互不覆盖。
            applicationIdSuffix = ".dev"
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "DEMO_MODE", "true")
            // Demo builds always discard local household identity and server defaults.
            buildConfigField("String", "DEFAULT_SYNC_TOKEN", literal(""))
            buildConfigField("String", "DEFAULT_LAN_URL", literal(""))
            buildConfigField("String", "DEFAULT_WAN_URL", literal(""))
            buildConfigField("String", "PERSON_A_NAME", literal("用户 A"))
            buildConfigField("String", "PERSON_B_NAME", literal("用户 B"))
            buildConfigField("String", "HOUSEHOLD_ID", literal("DEMOHOME"))
            buildConfigField("String", "CHARGING_MERCHANT", literal("示例充电服务有限公司"))
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")


    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.commons:commons-csv:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    // 单测里起一个真实 HTTP 服务器来验证 WebDAV 传输。
    // 不能用 JDK 的 com.sun.net.httpserver —— Android 单测的编译 classpath 是 android.jar，
    // 看不到 jdk.httpserver 模块，会直接编译失败。
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
