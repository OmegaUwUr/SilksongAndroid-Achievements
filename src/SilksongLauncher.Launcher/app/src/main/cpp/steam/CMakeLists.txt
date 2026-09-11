function(configure_steam_shim target output_name)
    add_library(${target} SHARED steam_api_shim.c)
    target_compile_features(${target} PRIVATE c_std_11)
    target_link_libraries(${target} PRIVATE log)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    set_target_properties(${target} PROPERTIES OUTPUT_NAME "${output_name}")
endfunction()

# Steamworks.NET's generated IL2CPP P/Invokes use the Windows-style module
# name "steam_api64" even on this translated Android build. Android's native
# resolver turns that into libsteam_api64.so, so shipping only libsteam_api.so
# leaves every Steamworks call unresolved before our shim can run.
#
# Keep both names as identical builds of the same shim: older/generated paths
# may ask for steam_api while current Steamworks.NET asks for steam_api64.
configure_steam_shim(steam_api steam_api)
configure_steam_shim(steam_api64 steam_api64)
