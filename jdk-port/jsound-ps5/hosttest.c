#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "DirectAudio.h"
static long long energy; static int blocks, peak;
int sceAudioOutInit(void){return 0;}
int sceAudioOutOpen(int a,int b,int c,unsigned d,unsigned e,unsigned f){return 7;}
int sceAudioOutOutput(int h,const void*buf){const short*s=buf;for(int i=0;i<512;i++){energy+=abs(s[i]);if(abs(s[i])>peak)peak=abs(s[i]);}blocks++;usleep(1000);return 0;}
void DAUDIO_AddAudioFormat(void*c,int a,int b,int d,float e,int f,int g,int h){}
int main(){
  void*a=DAUDIO_Open(0,0,1,DAUDIO_PCM,22050,8,1,1,0,0,0);
  void*b=DAUDIO_Open(0,0,1,DAUDIO_PCM,44100,16,4,2,1,0,0);
  if(!a||!b){puts("open failed");return 1;}
  printf("buffers %d %d, draining(stopped)=%d\n",DAUDIO_GetBufferSize(a,1),DAUDIO_GetBufferSize(b,1),DAUDIO_StillDraining(a,1));
  unsigned char u8[2205]; short s16[4410*2];
  for(int i=0;i<2205;i++)u8[i]=128+(int)(60*sin(i*0.2));
  for(int i=0;i<4410;i++){s16[i*2]=(short)(8000*sin(i*0.05));s16[i*2+1]=s16[i*2];}
  int wa=DAUDIO_Write(a,(char*)u8,sizeof u8), wb=DAUDIO_Write(b,(char*)s16,sizeof s16);
  DAUDIO_Start(a,1);DAUDIO_Start(b,1);
  usleep(300000);
  printf("wrote %d %d, blocks %d, peak %d, energy %lld, left %d %d, draining %d %d\n",wa,wb,blocks,peak,energy,
     DAUDIO_GetBufferSize(a,1)-DAUDIO_GetAvailable(a,1),DAUDIO_GetBufferSize(b,1)-DAUDIO_GetAvailable(b,1),DAUDIO_StillDraining(a,1),DAUDIO_StillDraining(b,1));
  DAUDIO_Stop(a,1);DAUDIO_Close(a,1);DAUDIO_Close(b,1);
  for(int i=0;i<70;i++){void*x=DAUDIO_Open(0,0,1,DAUDIO_PCM,8000,16,2,1,1,0,0);if(!x){printf("line %d refused (limit)\n",i);break;}}
  puts("ok");return 0;}
