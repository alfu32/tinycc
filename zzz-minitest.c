#include <stdio.h>

int main (int argc,const char ** argv){
    for(int i=0;i<argc;i++){
	printf("args[%d]=%s\n",i,argv[i]);
    }
}
