// On-device translation LLM: llama.cpp (git submodule llama.cpp/, pinned) behind a small JNI (src/main/cpp).
// arm64-v8a only; other ABIs simply have no on-device model (LocalLlm.isSupported is false there).
plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "app.karaza.llm"
    ndkVersion = "28.2.13676358" // same NDK as the engine's native layer

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    // CPU backend built for several ARM feature levels and picked at run time
                    // (dotprod / i8mm / SVE …), so one APK is fast on new phones and still runs on older ones.
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_CPU_KLEIDIAI=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
