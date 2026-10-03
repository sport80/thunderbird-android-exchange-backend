plugins {
    id(ThunderbirdPlugins.Library.jvm)
    alias(libs.plugins.android.lint)
}

dependencies {
    api(projects.backend.api)

    implementation(projects.core.common)
    implementation(projects.feature.mail.folder.api)

    testImplementation(projects.backend.testing)
    testImplementation(libs.junit)
    testImplementation(libs.assertk)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
