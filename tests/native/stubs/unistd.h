#pragma once
#include <cstdint>
#include <cstdlib>
using ssize_t = intptr_t;
inline ssize_t read(int, void*, size_t) { std::abort(); }
inline int close(int) { std::abort(); }
inline int usleep(unsigned int) { std::abort(); }
