cmake_minimum_required(VERSION 3.22.1)
project(traffic C)
# Keep the LGPL library dynamically linked and its corresponding source in the repository.
# Stripped at link (-s): these exact files are published and fingerprinted (build.gradle, NetEngine).
add_library(zdtun SHARED zdtun/zdtun.c zdtun/utils.c)
target_compile_definitions(zdtun PRIVATE ANDROID _LITTLE_ENDIAN NO_DEBUG)
target_link_options(zdtun PRIVATE -s -Wl,-z,max-page-size=16384)
add_library(traffic SHARED traffic.c)
target_include_directories(traffic PRIVATE zdtun)
target_link_libraries(traffic zdtun log)
target_link_options(traffic PRIVATE -s -Wl,-z,max-page-size=16384)
