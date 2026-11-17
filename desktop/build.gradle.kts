import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "id.gatra"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("uk.co.caprica:vlcj:4.8.3")
}

compose.desktop {
    application {
        mainClass = "id.gatra.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Gatra"
            packageVersion = "1.0.0"
            description = "Gatra - pemutar video tanpa iklan"
            vendor = "Gatra"
            includeAllModules = true

            windows {
                menuGroup = "Gatra"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f3b2a54-1d7e-4c8a-9a52-3e1f0c7d9b11"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
