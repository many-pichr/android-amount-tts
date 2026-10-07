plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.manypichr.khmeramountspeech"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // MP3 is stored uncompressed in the APK by default, so clips can be read via AssetFileDescriptor.
    androidResources {
        noCompress += "mp3"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }

            pom {
                name.set("KhmerAmountSpeech")
                description.set(
                    "Offline Khmer and English speech for money amounts, composed from pre-recorded audio."
                )
                url.set("https://github.com/many-pichr/android-amount-tts")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("many-pichr")
                        name.set("many.pichr")
                        email.set("many.pichr168@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/many-pichr/android-amount-tts")
                    connection.set("scm:git:https://github.com/many-pichr/android-amount-tts.git")
                }
            }
        }
    }
    repositories {
        // ./gradlew publishReleasePublicationToProjectRepository → build/repo (handy for inspection or a static Maven host)
        maven {
            name = "project"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
