/* TinyCC fallback for the compiler-provided MinGW aligned-allocation header. */
#ifndef __MM_MALLOC_H
#define __MM_MALLOC_H

#include <stdlib.h>
#include <malloc.h>

#ifndef _mm_malloc
static inline void *_mm_malloc(size_t size, size_t alignment)
{
    if (alignment == 1)
        return malloc(size);
    if (!(alignment & (alignment - 1)) && alignment < sizeof(void *))
        alignment = sizeof(void *);
    return _aligned_malloc(size, alignment);
}
#endif

#ifndef _mm_free
static inline void _mm_free(void *pointer)
{
    _aligned_free(pointer);
}
#endif

#endif /* __MM_MALLOC_H */
