plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)
    implementation(libs.kotlinxCoroutines)

    testImplementation(kotlin("test"))
}
