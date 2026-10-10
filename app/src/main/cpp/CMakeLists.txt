cmake_minimum_required(VERSION 3.22.1)

project("native-proxy")

add_library(
    native-proxy
    SHARED
    native-proxy.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    native-proxy
    ${log-lib}
)
