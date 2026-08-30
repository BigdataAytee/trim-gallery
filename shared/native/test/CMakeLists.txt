# Verification only; never part of a shipped artefact.
add_executable(test_metrics test_metrics.c)
# CXX for the link only: libvmaf bundles libsvm, which is C++.
set_target_properties(test_metrics PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(test_metrics PRIVATE trim_native vmaf m)
target_include_directories(test_metrics PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_test(
    NAME metrics_match_upstream
    COMMAND test_metrics
            ${CMAKE_CURRENT_SOURCE_DIR}/fixtures/ref.yuv
            ${CMAKE_CURRENT_SOURCE_DIR}/fixtures/dist.yuv
)
