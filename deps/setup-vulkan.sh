#!/bin/bash
# Fetch Vulkan headers and compile shaders for Android cross-build
set -e
cd "$(dirname "$0")"

if [ ! -d vulkan-headers/vulkan ]; then
    echo "Fetching Vulkan headers..."
    mkdir -p vulkan-headers
    git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git /tmp/vh && mv /tmp/vh/include vulkan-headers/vulkan && rm -rf /tmp/vh
    git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers.git /tmp/sh && mv /tmp/sh/include vulkan-headers/spirv && rm -rf /tmp/sh
fi

if [ ! -d vulkan-shaders-prebuilt ]; then
    echo "Compiling Vulkan shaders (requires glslc with GL_EXT_integer_dot_product)..."
    GLSLC="${GLSLC:-$(which glslc)}"
    SHADER_SRC=acestep.cpp/ggml/src/ggml-vulkan/vulkan-shaders
    g++ -std=c++17 -O2 -DGGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT \
        -o /tmp/vulkan-shaders-gen $SHADER_SRC/vulkan-shaders-gen.cpp
    mkdir -p vulkan-shaders-prebuilt
    /tmp/vulkan-shaders-gen --output-dir vulkan-shaders-prebuilt --target-hpp vulkan-shaders-prebuilt/ggml-vulkan-shaders.hpp
    for f in $SHADER_SRC/*.comp; do
        name=$(basename $f)
        /tmp/vulkan-shaders-gen --glslc "$GLSLC" --source "$f" \
            --output-dir vulkan-shaders-prebuilt \
            --target-hpp vulkan-shaders-prebuilt/ggml-vulkan-shaders.hpp \
            --target-cpp "vulkan-shaders-prebuilt/${name}.cpp" 2>/dev/null
    done
    echo "Generated $(ls vulkan-shaders-prebuilt/*.cpp | wc -l) shader files"
fi
