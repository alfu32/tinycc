#include <stdlib.h>
#include <mm_malloc.h>

extern int BCryptGenRandom(void *, unsigned char *, unsigned long, unsigned long);

int main(void)
{
    unsigned char bytes[16];
    return BCryptGenRandom(0, bytes, sizeof bytes, 2);
}
