#!/bin/bash
# Build ace-server for Android arm64 with Vulkan support
set -e
cd "$(dirname "$0")"

NDK="${ANDROID_NDK:-$HOME/android-sdk/ndk/27.0.12077973}"
CMAKE="${CMAKE:-$HOME/android-sdk/cmake/3.22.1/bin/cmake}"
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SYSROOT=$TOOLCHAIN/sysroot

VULKAN_INC="$(pwd)/vulkan-headers/vulkan/include"
SPIRV_INC="$(pwd)/vulkan-headers/spirv/include"
SHADERS_DIR="$(pwd)/vulkan-shaders-prebuilt"
VK_SO="$SYSROOT/usr/lib/aarch64-linux-android/29/libvulkan.so"

if [ ! -d vulkan-headers ] || [ ! -d vulkan-shaders-prebuilt ]; then
    echo "Run ./setup-vulkan.sh first"; exit 1
fi

# Build Vulkan shader static lib
if [ ! -f acestep.cpp/build-android/vk_obj/libggml-vulkan-prebuilt.a ]; then
    echo "Compiling Vulkan shaders..."
    CXX=$TOOLCHAIN/bin/aarch64-linux-android29-clang++
    AR=$TOOLCHAIN/bin/llvm-ar
    GGML=acestep.cpp/ggml
    mkdir -p acestep.cpp/build-android/vk_obj && cd acestep.cpp/build-android
    $CXX -std=c++17 -O3 -DNDEBUG -DGGML_USE_VULKAN=1 -DGGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT -DGGML_MAX_NAME=128 \
        -I../ggml/include -I../ggml/src -I../ggml/src/ggml-vulkan \
        -I"$VULKAN_INC" -I"$SPIRV_INC" -I"$SHADERS_DIR" \
        -c ../ggml/src/ggml-vulkan/ggml-vulkan.cpp -o vk_obj/ggml-vulkan.o
    ls "$SHADERS_DIR"/*.comp.cpp | xargs -P$(nproc) -I{} sh -c \
        "$CXX -std=c++17 -O3 -DNDEBUG -I\"$SHADERS_DIR\" -c {} -o vk_obj/\$(basename {} .cpp).o"
    $AR rcs vk_obj/libggml-vulkan-prebuilt.a vk_obj/*.o
    cd ../..
fi

VK_LIB="$(pwd)/acestep.cpp/build-android/vk_obj/libggml-vulkan-prebuilt.a"

cd acestep.cpp
rm -rf build-android-vk && mkdir build-android-vk && cd build-android-vk
$CMAKE .. \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DGGML_VULKAN=OFF -DGGML_CUDA=OFF -DGGML_METAL=OFF \
    -DGGML_STATIC=ON -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="-DGGML_USE_VULKAN=1 -DGGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT -I$VULKAN_INC -I$SPIRV_INC -I$SHADERS_DIR" \
    -DCMAKE_EXE_LINKER_FLAGS="$VK_LIB $VK_SO"
$CMAKE --build . --target ace-server -j$(nproc)

$TOOLCHAIN/bin/llvm-strip ace-server -o ../../app/src/main/jniLibs/arm64-v8a/libace-server.so
echo "Done: app/src/main/jniLibs/arm64-v8a/libace-server.so"
