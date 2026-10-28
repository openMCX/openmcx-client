cmake_minimum_required(VERSION 3.22)
project(openmcx-client VERSION 0.1.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The client links the protocol libraries of the server repository (mcx_sip,
# mcx_core, mcx_keying), built here from an openmcx-server checkout (the sibling repository by default).
set(OPENMCX_SERVER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../openmcx-server" CACHE PATH "openmcx-server checkout")
if(NOT EXISTS "${OPENMCX_SERVER_DIR}/src/sip/sip_engine.cpp")
    message(FATAL_ERROR "No openmcx-server checkout at ${OPENMCX_SERVER_DIR}; pass -DOPENMCX_SERVER_DIR=<path>")
endif()
set(BUILD_TESTING OFF CACHE BOOL "Build the openmcx-server unit tests" FORCE)
add_subdirectory("${OPENMCX_SERVER_DIR}" openmcx-server EXCLUDE_FROM_ALL)

# The MC client: one program for all three services (a UE runs MCPTT, MCVideo and MCData), one class declared in
# src/client/core/client.hpp, whose member functions are defined one topic per file: the shared core, then the
# MCPTT, MCVideo and MCData parts.
add_executable(mcx-client
    # core
    src/client/core/activation.cpp
    src/client/core/client.cpp
    src/client/core/commands.cpp
    src/client/core/group_management.cpp
    src/client/core/location.cpp
    src/client/core/log_off.cpp
    src/client/core/main.cpp
    src/client/core/protected_call.cpp
    src/client/core/seal_location.cpp
    src/client/core/sip_dispatch.cpp
    src/client/core/tokens.cpp
    src/client/core/xml_protection.cpp
    # mcptt
    src/client/mcptt/adhoc.cpp
    src/client/mcptt/call_media.cpp
    src/client/mcptt/call_suspension.cpp
    src/client/mcptt/functional_alias.cpp
    src/client/mcptt/incoming_calls.cpp
    src/client/mcptt/live_audio.cpp
    src/client/mcptt/priority.cpp
    src/client/mcptt/private_call.cpp
    src/client/mcptt/private_services.cpp
    src/client/mcptt/regroup.cpp
    src/client/mcptt/service.cpp
    src/client/mcptt/speech_capture.cpp
    # mcvideo
    src/client/mcvideo/call.cpp
    src/client/mcvideo/functional_alias.cpp
    src/client/mcvideo/group_selection.cpp
    src/client/mcvideo/live_video.cpp
    src/client/mcvideo/location.cpp
    src/client/mcvideo/service.cpp
    src/client/mcvideo/session.cpp
    # mcdata
    src/client/mcdata/alert.cpp
    src/client/mcdata/dispatch.cpp
    src/client/mcdata/file_distribution.cpp
    src/client/mcdata/ipconn.cpp
    src/client/mcdata/media.cpp
    src/client/mcdata/media_session.cpp
    src/client/mcdata/message_store.cpp
    src/client/mcdata/priority.cpp
    src/client/mcdata/protection.cpp
    src/client/mcdata/release.cpp
    src/client/mcdata/service.cpp)
target_include_directories(mcx-client PRIVATE src/client/core)
target_compile_options(mcx-client PRIVATE -Wall -Wextra -Wpedantic)
# Link-time optimisation: the split into files must not cost the inlining the single file had.
set_target_properties(mcx-client PROPERTIES INTERPROCEDURAL_OPTIMIZATION ON)
target_link_libraries(mcx-client PRIVATE mcx_sip)

# Unit tests of the client's stateless rules (src/client/core/client_rules.hpp); the lab scenarios of openmcx-server cover
# the client's behaviour.
enable_testing()
add_executable(test_client_rules tests/test_client_rules.cpp)
target_include_directories(test_client_rules PRIVATE src/client/core)
target_compile_options(test_client_rules PRIVATE -Wall -Wextra -Wpedantic)
add_test(NAME client_rules COMMAND test_client_rules)

# The background job of the sign-in (src/client/core/handoff.hpp).
find_package(Threads REQUIRED)
add_executable(test_handoff tests/test_handoff.cpp)
target_include_directories(test_handoff PRIVATE src/client/core)
target_compile_options(test_handoff PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(test_handoff PRIVATE Threads::Threads)
add_test(NAME handoff COMMAND test_handoff)
