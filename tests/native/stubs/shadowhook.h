#pragma once
#include <cstdlib>
// Host regression tests must never install hooks or touch a real process.
inline void* shadowhook_hook_sym_name(const char*, const char*, void*, void**) { std::abort(); }
inline int shadowhook_get_errno() { return 0; }
inline const char* shadowhook_to_errmsg(int) { return "host stub"; }
