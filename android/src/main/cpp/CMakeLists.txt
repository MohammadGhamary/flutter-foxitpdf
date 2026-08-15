cmake_minimum_required(VERSION 3.18.1)
project(foxitlic)

include(FetchContent)

# mbedTLS: small, permissive-licensed (Apache 2.0), pure C, easy to vendor
# into an NDK build without needing prebuilt Android binaries. Pin an exact
# tag so the build is reproducible -- bump deliberately, not automatically.
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(MBEDTLS_AS_SUBPROJECT ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG        v3.6.0
)
FetchContent_MakeAvailable(mbedtls)

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
