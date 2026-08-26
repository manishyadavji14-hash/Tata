cmake_minimum_required(VERSION 3.22)
project(bitperfect_engine VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# NDK r29 compatibility: suppress deprecated declarations warnings from NDK headers
if(ANDROID)
    add_compile_options(-Wno-deprecated-declarations)
endif()

# Option for standalone testing (without Android NDK)
option(STANDALONE_TEST "Build standalone tests with Google Test" OFF)

# Source files
set(USB_SOURCES
    usb/usb_audio_device.cpp
    usb/usb_control.cpp
    usb/isochronous_transfer.cpp
    usb/usbdevfs_iso_backend.cpp
)

set(PCM_SOURCES
    pcm/pcm_engine.cpp
)

set(AUDIO_SOURCES
    audio/sample_rate_manager.cpp
    audio/playback_mode.cpp
    audio/format_detector.cpp
    audio/gapless_engine.cpp
    audio/replaygain.cpp
)

set(DECODER_SOURCES
    decoder/wav_decoder.cpp
    decoder/flac_decoder.cpp
    decoder/decoder_factory.cpp
)

set(DSD_SOURCES
    dsd/dsf_parser.cpp
    dsd/dsd_stream.cpp
)

set(DOP_SOURCES
    dop/dop_encoder.cpp
)

set(NATIVE_DSD_SOURCES
    native_dsd/native_dsd_transport.cpp
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
    ${DECODER_SOURCES}
    ${DSD_SOURCES}
    ${DOP_SOURCES}
    ${NATIVE_DSD_SOURCES}
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

    add_executable(test_dop_encoder tests/test_dop_encoder.cpp)
    target_link_libraries(test_dop_encoder PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_dop_encoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_dsf_parser tests/test_dsf_parser.cpp)
    target_link_libraries(test_dsf_parser PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_dsf_parser PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_native_dsd tests/test_native_dsd.cpp)
    target_link_libraries(test_native_dsd PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_native_dsd PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_playback_mode tests/test_playback_mode.cpp)
    target_link_libraries(test_playback_mode PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_playback_mode PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_wav_decoder tests/test_wav_decoder.cpp)
    target_link_libraries(test_wav_decoder PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_wav_decoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_flac_decoder tests/test_flac_decoder.cpp)
    target_link_libraries(test_flac_decoder PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_flac_decoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_gapless tests/test_gapless.cpp)
    target_link_libraries(test_gapless PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_gapless PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_replaygain tests/test_replaygain.cpp)
    target_link_libraries(test_replaygain PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_replaygain PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_isochronous_transfer tests/test_isochronous_transfer.cpp)
    target_link_libraries(test_isochronous_transfer PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_isochronous_transfer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_usb_iso_backend tests/test_usb_iso_backend.cpp)
    target_link_libraries(test_usb_iso_backend PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_usb_iso_backend PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_buffer_manager tests/test_buffer_manager.cpp)
    target_link_libraries(test_buffer_manager PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_buffer_manager PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(test_full_pipeline tests/test_full_pipeline.cpp)
    target_link_libraries(test_full_pipeline PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(test_full_pipeline PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(benchmark_ring_buffer tests/benchmark_ring_buffer.cpp)
    target_link_libraries(benchmark_ring_buffer PRIVATE bitperfect_engine GTest::gtest_main)
    target_include_directories(benchmark_ring_buffer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    # Add tests to CTest
    include(GoogleTest)
    gtest_discover_tests(test_ring_buffer)
    gtest_discover_tests(test_pcm_engine)
    gtest_discover_tests(test_usb_descriptors)
    gtest_discover_tests(test_sample_rate)
    gtest_discover_tests(test_dop_encoder)
    gtest_discover_tests(test_dsf_parser)
    gtest_discover_tests(test_native_dsd)
    gtest_discover_tests(test_playback_mode)
    gtest_discover_tests(test_wav_decoder)
    gtest_discover_tests(test_flac_decoder)
    gtest_discover_tests(test_gapless)
    gtest_discover_tests(test_replaygain)
    gtest_discover_tests(test_isochronous_transfer)
    gtest_discover_tests(test_usb_iso_backend)
    gtest_discover_tests(test_buffer_manager)
    gtest_discover_tests(test_full_pipeline)
    gtest_discover_tests(benchmark_ring_buffer)

    # Link threading library for ring buffer tests
    find_package(Threads REQUIRED)
    target_link_libraries(test_ring_buffer PRIVATE Threads::Threads)
    target_link_libraries(test_dop_encoder PRIVATE Threads::Threads)
    target_link_libraries(test_gapless PRIVATE Threads::Threads)
    target_link_libraries(test_isochronous_transfer PRIVATE Threads::Threads)
    target_link_libraries(test_usb_iso_backend PRIVATE Threads::Threads)
    target_link_libraries(test_buffer_manager PRIVATE Threads::Threads)
    target_link_libraries(test_full_pipeline PRIVATE Threads::Threads)
    target_link_libraries(benchmark_ring_buffer PRIVATE Threads::Threads)

else()
    # Android NDK build
    add_library(bitperfect_engine SHARED ${ALL_ENGINE_SOURCES})
    target_include_directories(bitperfect_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

    # Link Android libraries
    find_library(log-lib log)
    target_link_libraries(bitperfect_engine PRIVATE ${log-lib})
endif()
