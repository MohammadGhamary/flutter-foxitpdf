cmake_minimum_required(VERSION 3.18.1)
project(foxitlic)

# mbedTLS is vendored locally instead of being fetched via git at build
# time (FetchContent + git clone was unreliable on Windows -- long/deep
# project paths hit MAX_PATH during the clone, and the build has no
# guaranteed network access anyway). Download the v3.6.0 source zip from
# https://github.com/Mbed-TLS/mbedtls/releases/tag/v3.6.0 and extract it
# so this exact path exists:
#   android/src/main/cpp/third_party/mbedtls/CMakeLists.txt
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(MBEDTLS_AS_SUBPROJECT ON CACHE BOOL "" FORCE)

set(MBEDTLS_VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls")
if(NOT EXISTS "${MBEDTLS_VENDOR_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "mbedTLS not found at ${MBEDTLS_VENDOR_DIR}. Download the v3.6.0 "
            "source zip from https://github.com/Mbed-TLS/mbedtls/releases/tag/v3.6.0 "
            "and extract it there (so CMakeLists.txt sits directly inside that folder).")
endif()

add_subdirectory("${MBEDTLS_VENDOR_DIR}" mbedtls_build)

add_library(foxitlic SHARED
        foxit_native_crypto.cpp
)

target_link_libraries(foxitlic
        mbedcrypto
        log
)

# Strip symbol names as much as possible in release builds. Combined with
# the JNI_OnLoad/RegisterNatives approach in foxit_native_crypto.cpp, this
# keeps the exported symbol table free of anything that hints at what the
# library does.
target_compile_options(foxitlic PRIVATE
        $<$<CONFIG:Release>:-fvisibility=hidden -fvisibility-inlines-hidden>
)
set_target_properties(foxitlic PROPERTIES
        LINK_FLAGS_RELEASE "-Wl,--exclude-libs,ALL -Wl,-s"
)