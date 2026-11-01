cmake_minimum_required(VERSION 3.22.1)
project("karaza_llm" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(karaza_llm SHARED karaza_llm.cpp)
target_include_directories(karaza_llm PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(karaza_llm llama ggml android log dl)
