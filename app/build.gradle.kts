plugins {
    id("com.android.application")
}

android {
    namespace = "dev.voxkey.bench"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.voxkey.bench"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // On-device benchmarks only; the shipping app never links this.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // VOXKEY_VULKAN=ON is the single switch (see CMakeLists.txt).
                arguments += listOf("-DVOXKEY_VULKAN=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
