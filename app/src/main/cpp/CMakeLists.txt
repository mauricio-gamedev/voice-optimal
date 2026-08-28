cmake_minimum_required(VERSION 3.22.1)

project(clearmic_audio LANGUAGES C CXX)

include(FetchContent)

# ClearMic AI V1 uses the upstream RNNoise implementation pinned to a known
# commit for reproducible Android builds. It is built as a private static
# library and linked only into libclearmic_audio.so.
FetchContent_Declare(
    rnnoise_source
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG 70f1d256acd4b34a572f999a05c87bf00b67730d
    GIT_SHALLOW TRUE
    GIT_PROGRESS FALSE
)
FetchContent_GetProperties(rnnoise_source)
if(NOT rnnoise_source_POPULATED)
    FetchContent_Populate(rnnoise_source)
endif()

add_library(
    rnnoise
    STATIC
    ${rnnoise_source_SOURCE_DIR}/src/denoise.c
    ${rnnoise_source_SOURCE_DIR}/src/rnn.c
    ${rnnoise_source_SOURCE_DIR}/src/pitch.c
    ${rnnoise_source_SOURCE_DIR}/src/kiss_fft.c
    ${rnnoise_source_SOURCE_DIR}/src/celt_lpc.c
    ${rnnoise_source_SOURCE_DIR}/src/nnet.c
    ${rnnoise_source_SOURCE_DIR}/src/nnet_default.c
    ${rnnoise_source_SOURCE_DIR}/src/parse_lpcnet_weights.c
    ${rnnoise_source_SOURCE_DIR}/src/rnnoise_data.c
    ${rnnoise_source_SOURCE_DIR}/src/rnnoise_tables.c
)
set_target_properties(rnnoise PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(
    rnnoise
    PUBLIC ${rnnoise_source_SOURCE_DIR}/include
    PRIVATE ${rnnoise_source_SOURCE_DIR}/src
)
target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)
target_compile_options(rnnoise PRIVATE -O3 -ffast-math)
target_link_libraries(rnnoise PRIVATE m)

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
    rnnoise
    aaudio
    log
    android
    m
)
