#pragma once
inline int pthread_self() { return 0; }
inline int pthread_setname_np(int, const char*) { return 0; }
