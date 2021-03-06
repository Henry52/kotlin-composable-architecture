plugins {
    id("kotlin")
}

dependencies {
    implementation("io.arrow-kt:arrow-optics:0.10.5")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.3.72")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.3.7")
    implementation(project(":composable-architecture"))
}
