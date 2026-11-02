// Vendored arkon/FlexibleAdapter (Apache-2.0); see README.md for why it lives here.
plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "eu.davidea.flexibleadapter"
}

dependencies {
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
}
