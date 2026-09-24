#include <stdlib.h>
#include <mm_malloc.h>
#include <uchar.h>

extern int BCryptGenRandom(void *, unsigned char *, unsigned long, unsigned long);

int main(void)
{
    unsigned char bytes[16];
    mbstate_t state = {0};
    char16_t c16;
    char32_t c32;
    char utf8[8];
    size_t result;

    result = mbrtoc16(&c16, "A", 1, &state);
    result += c16rtomb(utf8, c16, &state);
    result += mbrtoc32(&c32, "B", 1, &state);
    result += c32rtomb(utf8, c32, &state);
    return BCryptGenRandom(0, bytes, sizeof bytes, 2) + (int)result;
}
