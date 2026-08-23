cmake_minimum_required(VERSION 3.22.1)

project(clearmic_audio LANGUAGES CXX)

add_library(
    clearmic_audio
    SHARED
    clearmic_audio.cpp
)

target_compile_features(clearmic_audio PRIVATE cxx_std_17)
target_compile_options(
    clearmic_audio
    PRIVATE
    -O3
    -fno-exceptions
    -fno-rtti
    -ffast-math
)

target_link_libraries(
    clearmic_audio
    PRIVATE
    aaudio
    log
    android
)
