cmake_minimum_required(VERSION 3.18)
project(bitperfect_engine VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Option for standalone testing (without Android NDK)
option(STANDALONE_TEST "Build standalone tests with Google Test" OFF)

# Source files
set(USB_SOURCES
    usb/usb_audio_device.cpp
    usb/usb_control.cpp
    usb/isochronous_transfer.cpp
)

set(PCM_SOURCES
    pcm/pcm_engine.cpp
)

set(AUDIO_SOURCES
    audio/sample_rate_manager.cpp
)

set(BUFFER_SOURCES
    buffer/ring_buffer.cpp
    buffer/audio_buffer_manager.cpp
)

set(DIAGNOSTICS_SOURCES
    diagnostics/diagnostics.cpp
)

set(JNI_SOURCES
    jni/native_bridge.cpp
)

set(ALL_ENGINE_SOURCES
    ${USB_SOURCES}
    ${PCM_SOURCES}
    ${AUDIO_SOURCES}
    ${BUFFER_SOURCES}
    ${DIAGNOSTICS_SOURCES}
    ${JNI_SOURCES}
)

if(STANDALONE_TEST)
    # Standalone test build - no Android dependencies
    add_library(bitperfect_engine STATIC ${ALL_ENGINE_SOURCES})
    target_include_directories(bitperfect_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(bitperfect_engine PRIVATE STANDALONE_TEST=1)

    # Fetch Google Test
    include(FetchContent)
    FetchContent_Declare(
        googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.14.0
    )
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    enable_testing()

    # Test executables
    add_executable(test_ring_buffer tests/test_ring_buffer.cpp)
    target_link_libraries(test_ring_buffer PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_ring_buffer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_pcm_engine tests/test_pcm_engine.cpp)
    target_link_libraries(test_pcm_engine PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_pcm_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_usb_descriptors tests/test_usb_descriptors.cpp)
    target_link_libraries(test_usb_descriptors PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_usb_descriptors PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_sample_rate tests/test_sample_rate.cpp)
    target_link_libraries(test_sample_rate PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_sample_rate PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    # Add tests to CTest
    include(GoogleTest)
    gtest_discover_tests(test_ring_buffer)
    gtest_discover_tests(test_pcm_engine)
    gtest_discover_tests(test_usb_descriptors)
    gtest_discover_tests(test_sample_rate)

    # Link threading library for ring buffer tests
    find_package(Threads REQUIRED)
    target_link_libraries(test_ring_buffer PRIVATE Threads::Threads)

else()
    # Android NDK build
    add_library(bitperfect_engine SHARED ${ALL_ENGINE_SOURCES})
    target_include_directories(bitperfect_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

    # Link Android libraries
    find_library(log-lib log)
    target_link_libraries(bitperfect_engine PRIVATE ${log-lib})
endif()
