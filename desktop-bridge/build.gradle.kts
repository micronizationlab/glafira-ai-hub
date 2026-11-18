plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.micronizationlab.glafira.desktop.DesktopBridgeCliKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
