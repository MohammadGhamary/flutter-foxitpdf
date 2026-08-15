cmake_minimum_required(VERSION 3.18.1)
project(foxitlic)

include(FetchContent)

# mbedTLS: small, permissive-licensed (Apache 2.0), pure C, easy to vendor
# into an NDK build without needing prebuilt Android binaries.
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(MBEDTLS_AS_SUBPROJECT ON CACHE BOOL "" FORCE)

# FIX (supply-chain hardening): a Git *tag* like "v3.6.0" can be moved by a
# force-push on the upstream repo (or, in a worst case, by a compromised
# maintainer account), silently swapping in different source on a later
# clean build without this file changing at all. Pin to the full 40-char
# commit SHA instead, so the source that gets compiled is exactly the
# commit you reviewed.
#
# To resolve the SHA for a given tag yourself (do this once, then hardcode
# the result below -- don't fetch-and-trust at build time):
#   git ls-remote https://github.com/Mbed-TLS/mbedtls.git refs/tags/v3.6.5
#
# Also consider whether you actually want v3.6.0: the 3.6 LTS branch has
# received several subsequent patch releases (3.6.3, 3.6.5, ...) that
# include security fixes per the upstream release notes. Prefer pinning to
# the latest 3.6.x LTS patch rather than the original 3.6.0 tag unless you
# have a specific reason to stay on 3.6.0.
FetchContent_Declare(
        mbedtls
        GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
        GIT_TAG        <PIN-THIS-TO-A-FULL-COMMIT-SHA-NOT-A-MOVABLE-TAG>
        GIT_SHALLOW    TRUE
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
#
# FIX: added -ffunction-sections/-fdata-sections (paired with
# --gc-sections below) to let the linker drop unreferenced code/data,
# shrinking the binary and reducing what's left to reverse-engineer.
target_compile_options(foxitlic PRIVATE
        $<$<CONFIG:Release>:-fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections>
)
set_target_properties(foxitlic PROPERTIES
        LINK_FLAGS_RELEASE "-Wl,--exclude-libs,ALL -Wl,-s -Wl,--gc-sections"
)