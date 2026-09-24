/**
 * This file has no copyright assigned and is placed in the Public Domain.
 * This file is part of the TinyCC package.
 * No warranty is given; refer to the file DISCLAIMER within this package.
 */

#ifndef _INC_UCHAR
#define _INC_UCHAR

#include <stddef.h>
#include <wchar.h>

/*
 * The following defines are only valid when C11 (-std=c11) is used.
 *
 * ... a wide character constant prefixed by the letter u or U has type char16_t
 * or char32_t, respectively, unsigned integer types defined in the <uchar.h>
 * header.
 */

#if __STDC_VERSION__ >= 201112L
/**
 * __STDC_UTF_16__ The integer constant 1, intended to indicate that
 * values of type char16_t are UTF-16 encoded.
 */
#define __STDC_UTF_16__ 1
/**
 * __STDC_UTF_32__ The integer constant 1, intended to indicate that
 * values of type char32_t are UTF-32 encoded.
 */
#define __STDC_UTF_32__ 1

typedef unsigned short char16_t;
typedef unsigned int char32_t;

/* The bundled Windows UCRT provides the C11 UTF conversion functions. */
size_t __cdecl mbrtoc16(char16_t *, const char *, size_t, mbstate_t *);
size_t __cdecl c16rtomb(char *, char16_t, mbstate_t *);
size_t __cdecl mbrtoc32(char32_t *, const char *, size_t, mbstate_t *);
size_t __cdecl c32rtomb(char *, char32_t, mbstate_t *);
#endif /* __STDC_VERSION__ >= 201112L */
#endif /* _INC_UCHAR */
