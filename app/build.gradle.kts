import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val ngrokNativeArm64 by configurations.creating { isTransitive = false }
val ngrokNativeArmv7 by configurations.creating { isTransitive = false }

abstract class ExtractNgrokJni : DefaultTask() {
  @get:InputFiles abstract val arm64Artifacts: ConfigurableFileCollection
  @get:InputFiles abstract val armv7Artifacts: ConfigurableFileCollection
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
  @get:Inject abstract val archives: ArchiveOperations
  @get:Inject abstract val files: FileSystemOperations

  @TaskAction fun extract() {
    files.sync {
      into(outputDirectory)
      if (!arm64Artifacts.isEmpty) {
        from(archives.zipTree(arm64Artifacts.singleFile)) {
          include("libngrok_java.so")
          into("arm64-v8a")
        }
      }
      if (!armv7Artifacts.isEmpty) {
        from(archives.zipTree(armv7Artifacts.singleFile)) {
          include("libngrok_java.so")
          into("armeabi-v7a")
        }
      }
    }
  }
}

val ngrokNativeClasses by tasks.registering(Jar::class) {
  archiveFileName.set("ngrok-native-android-classes.jar")
  destinationDirectory.set(layout.buildDirectory.dir("generated/ngrok"))
  from(provider {
    if (!ngrokNativeArm64.isEmpty) zipTree(ngrokNativeArm64.singleFile) else files()
  }) {
    include("com/**", "native.properties")
    exclude("com/ngrok/Runtime*.class")
  }
}

val extractNgrokJni by tasks.registering(ExtractNgrokJni::class) {
  arm64Artifacts.from(ngrokNativeArm64)
  armv7Artifacts.from(ngrokNativeArmv7)
  outputDirectory.set(layout.buildDirectory.dir("generated/ngrok/jniLibs"))
}

androidComponents.onVariants { variant ->
  variant.sources.jniLibs?.addGeneratedSourceDirectory(extractNgrokJni, ExtractNgrokJni::outputDirectory)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.proxyengine.jfnwkq"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)

  implementation("com.ngrok:ngrok-java:1.0.0")
  ngrokNativeArm64("com.ngrok:ngrok-java-native:1.0.0:linux-android-aarch_64")
  ngrokNativeArmv7("com.ngrok:ngrok-java-native:1.0.0:linux-android-armv7")
  implementation(files(ngrokNativeClasses))
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
