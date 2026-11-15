import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** -PpleosSdk=true: Pleos NaviHelper 를 포함한 차량용 빌드. 기본 빌드는 내비 연결 없음. */
val pleosSdk = providers.gradleProperty("pleosSdk").orNull == "true"

android {
    sourceSets["main"].java.srcDir(if (pleosSdk) "src/pleos/java" else "src/nopleos/java")
    // 차량용 빌드에만 Pleos 내비 권한 매니페스트를 합친다(빌드 타입 매니페스트는 main 과 병합됨).
    if (pleosSdk) listOf("debug", "release").forEach { sourceSets[it].manifest.srcFile("src/pleos/AndroidManifest.xml") }
    // Pleos 에뮬레이터 SDK 확인용 계측 코드(차량용 빌드에서만)
    if (pleosSdk) sourceSets["androidTest"].java.srcDir("src/androidTestPleos/java")
    namespace = "com.jiye.dropmate"
    // 차량 주행 제한 상태(CarUxRestrictions). 차량이 아닌 기기에서는 쓰지 않는다(매니페스트 required=false)
    useLibrary("android.car")
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jiye.dropmate"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.4.0-mj3"
    }
    // 공모전 제출용 release 서명. keystore.properties(storeFile·storePassword·keyAlias·keyPassword, git 제외)가 있으면 그 키로,
    // 없으면 디버그 키로 서명해 설치 가능한 release APK 를 만든다.
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (ksFile.exists()) create("release") {
            val p = Properties().apply { ksFile.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":engine"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // 2026-11-12: 3D 건물 지도(MapLibre 오픈소스 SDK, OpenFreeMap 벡터 타일)
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    if (pleosSdk) implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 2026-11-12 차량 위치(현대차 SDK 활용·내 위치). AAR 매니페스트가 FUSED_LOCATION_BINDING 권한과 automotive 기능을 병합한다
    if (pleosSdk) implementation("ai.pleos.playground:FusedLocation:1.1.3")
    // 2026-11-14 음성 안내: Pleos TextToSpeech SDK(ON_DEVICE 모드, 앱 등록 불필요)
    if (pleosSdk) implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}
