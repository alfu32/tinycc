extern void *__acrt_iob_func(unsigned int);

int main(void)
{
    return __acrt_iob_func(0) == 0;
}
