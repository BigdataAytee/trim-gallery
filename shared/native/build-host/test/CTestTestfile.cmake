# CMake generated Testfile for 
# Source directory: /home/user/trim-gallery/shared/native/test
# Build directory: /home/user/trim-gallery/shared/native/build-host/test
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
add_test([=[metrics_match_upstream]=] "/home/user/trim-gallery/shared/native/build-host/test/test_metrics" "/home/user/trim-gallery/shared/native/test/fixtures/ref.yuv" "/home/user/trim-gallery/shared/native/test/fixtures/dist.yuv")
set_tests_properties([=[metrics_match_upstream]=] PROPERTIES  _BACKTRACE_TRIPLES "/home/user/trim-gallery/shared/native/test/CMakeLists.txt;8;add_test;/home/user/trim-gallery/shared/native/test/CMakeLists.txt;0;")
