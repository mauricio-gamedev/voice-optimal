cmake_minimum_required(VERSION 3.22.1)

project(clearmic_audio LANGUAGES C CXX)

include(FetchContent)

# ClearMic AI V1 uses upstream RNNoise pinned to one source commit and one
# model checksum. RNNoise intentionally does not keep its generated model C
# files in git; upstream autogen.sh downloads this exact archive as well.
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

set(RNNOISE_MODEL_SHA 0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37)
FetchContent_Declare(
    rnnoise_model
    URL https://media.xiph.org/rnnoise/models/rnnoise_data-${RNNOISE_MODEL_SHA}.tar.gz
    URL_HASH SHA256=${RNNOISE_MODEL_SHA}
)
FetchContent_GetProperties(rnnoise_model)
if(NOT rnnoise_model_POPULATED)
    FetchContent_Populate(rnnoise_model)
endif()

# The official model archive is made to be extracted at the RNNoise source
# root. Keep a root fallback here so the build remains deterministic if Xiph
# packages only the generated pair instead of the src/ directory wrapper.
set(RNNOISE_MODEL_C "${rnnoise_model_SOURCE_DIR}/src/rnnoise_data.c")
set(RNNOISE_MODEL_INCLUDE "${rnnoise_model_SOURCE_DIR}/src")
if(NOT EXISTS "${RNNOISE_MODEL_C}")
    set(RNNOISE_MODEL_C "${rnnoise_model_SOURCE_DIR}/rnnoise_data.c")
    set(RNNOISE_MODEL_INCLUDE "${rnnoise_model_SOURCE_DIR}")
endif()
if(NOT EXISTS "${RNNOISE_MODEL_C}")
    message(FATAL_ERROR "RNNoise model archive did not contain rnnoise_data.c")
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
    ${RNNOISE_MODEL_C}
    ${rnnoise_source_SOURCE_DIR}/src/rnnoise_tables.c
)
set_target_properties(rnnoise PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(
    rnnoise
    PUBLIC ${rnnoise_source_SOURCE_DIR}/include
    PRIVATE
    ${RNNOISE_MODEL_INCLUDE}
    ${rnnoise_source_SOURCE_DIR}/src
)
target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)
# RNNoise inherits Opus DSP helpers that explicitly reject -ffast-math
# unless FLOAT_APPROX is enabled. Keep strict IEEE math here for safety.
target_compile_options(rnnoise PRIVATE -O3)
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

# Dedicated JNI library for the Shizuku AudioPolicy injector. It processes
# caller-provided PCM frames and deliberately does not own/open an AAudio stream.
add_library(
    clearmic_ai_stream
    SHARED
    ai_stream_processor.cpp
)
target_compile_features(clearmic_ai_stream PRIVATE cxx_std_17)
target_compile_options(
    clearmic_ai_stream
    PRIVATE
    -O3
    -fno-exceptions
    -fno-rtti
)
target_link_libraries(
    clearmic_ai_stream
    PRIVATE
    rnnoise
    log
    android
    m
)
