/* PS5 audio output for Java Sound (the "direct audio" device of libjsound).
 *
 * Java opens many lines in many formats; the PS5 mixer wants 48 kHz 16-bit
 * stereo in fixed blocks. Every open line has a small ring buffer that Java
 * fills. One mixer thread converts, resamples and adds all running lines
 * together and hands the result to a single sceAudioOut port, whose
 * sceAudioOutOutput() call also paces the thread. Playback only. */
#define USE_ERROR
#define USE_TRACE
#include "DirectAudio.h"

#if USE_DAUDIO == TRUE

#include <pthread.h>
#include <stdlib.h>
#include <unistd.h>

int sceAudioOutInit(void);
int sceAudioOutOpen(int user_id, int port_type, int index, unsigned int frames,
                    unsigned int rate, unsigned int format);
int sceAudioOutOutput(int handle, const void *buf);

#define OUT_RATE   48000
#define OUT_FRAMES 256
#define MAX_LINES  64

typedef struct {
    int rate, channels, bytes, isSigned, bigEndian, frameSize;
    unsigned char *ring;
    int size, head, fill;          /* bytes; head = read position */
    volatile int running;
    double phase, step;            /* resampler position between prev and cur */
    int prev[2], cur[2];
} Line;

/* g_lock guards the line table and every line's ring buffer. */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static Line *g_lines[MAX_LINES];
static int g_port = -1, g_mixer_started;
static pthread_t g_mixer;

/* Takes one frame from the ring as 16-bit stereo. Returns 0 when it is empty. */
static int pop_frame(Line *l, int out[2]) {
    int v[2] = {0, 0};

    if (l->fill < l->frameSize) return 0;
    for (int c = 0; c < l->channels && c < 2; c++) {
        const unsigned char *p = l->ring + (l->head + c * l->bytes) % l->size;
        const unsigned char *q = l->ring + (l->head + c * l->bytes + 1) % l->size;
        if (l->bytes == 1) {
            v[c] = l->isSigned ? (signed char)p[0] * 256 : ((int)p[0] - 128) * 256;
        } else {
            v[c] = l->bigEndian ? (short)((p[0] << 8) | q[0]) : (short)((q[0] << 8) | p[0]);
        }
    }
    if (l->channels == 1) v[1] = v[0];
    l->head = (l->head + l->frameSize) % l->size;
    l->fill -= l->frameSize;
    out[0] = v[0];
    out[1] = v[1];
    return 1;
}

static void *mixer(void *arg) {
    static int acc[OUT_FRAMES * 2];
    short block[OUT_FRAMES * 2];

    for (;;) {
        memset(acc, 0, sizeof acc);
        pthread_mutex_lock(&g_lock);
        for (int n = 0; n < MAX_LINES; n++) {
            Line *l = g_lines[n];
            if (!l || !l->running) continue;
            for (int i = 0; i < OUT_FRAMES; i++) {
                while (l->phase >= 1.0) {
                    l->prev[0] = l->cur[0];
                    l->prev[1] = l->cur[1];
                    if (!pop_frame(l, l->cur)) { l->cur[0] = 0; l->cur[1] = 0; }
                    l->phase -= 1.0;
                }
                acc[i * 2]     += (int)(l->prev[0] + (l->cur[0] - l->prev[0]) * l->phase);
                acc[i * 2 + 1] += (int)(l->prev[1] + (l->cur[1] - l->prev[1]) * l->phase);
                l->phase += l->step;
            }
        }
        pthread_mutex_unlock(&g_lock);
        for (int i = 0; i < OUT_FRAMES * 2; i++) {
            int s = acc[i];
            block[i] = (short)(s > 32767 ? 32767 : s < -32768 ? -32768 : s);
        }
        /* Normally waits for the console's mixer (about 5 ms). If the port is
         * missing or the call fails it returns at once, so wait here instead
         * of spinning a processor core. */
        if (g_port < 1 || sceAudioOutOutput(g_port, block) < 0) usleep(5333);
    }
    return 0;
}

/* Opens the one output port and starts the mixer thread, once. Called with g_lock held. */
static int start_output(void) {
    if (g_mixer_started) return 1;
    sceAudioOutInit();
    /* system user, main port, 48 kHz, 16-bit stereo */
    g_port = sceAudioOutOpen(0xff, 0, 0, OUT_FRAMES, OUT_RATE, 1);
    if (g_port < 1) {
        ERROR1("PS5 audio: sceAudioOutOpen failed: 0x%08x\n", g_port);
    }
    if (pthread_create(&g_mixer, 0, mixer, 0)) return 0;
    g_mixer_started = 1;
    return 1;
}

INT32 DAUDIO_GetDirectAudioDeviceCount() {
    return 1;
}

INT32 DAUDIO_GetDirectAudioDeviceDescription(INT32 mixerIndex, DirectAudioDeviceDescription *d) {
    if (mixerIndex != 0) return FALSE;
    d->deviceID = 0;
    d->maxSimulLines = MAX_LINES;
    strncpy(d->name, "PS5 Audio", DAUDIO_STRING_LENGTH);
    strncpy(d->vendor, "PS5", DAUDIO_STRING_LENGTH);
    strncpy(d->description, "PS5 main audio output", DAUDIO_STRING_LENGTH);
    strncpy(d->version, "2", DAUDIO_STRING_LENGTH);
    return TRUE;
}

void DAUDIO_GetFormats(INT32 mixerIndex, INT32 deviceID, int isSource, void *creator) {
    /* -1 means "any rate": Java asks for lines that way (AudioSystem.getClip), and the
     * mixer resamples whatever rate the line is then opened with. */
    static const int rates[] = {-1, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};

    if (mixerIndex != 0 || !isSource) return;
    for (unsigned r = 0; r < sizeof rates / sizeof rates[0]; r++) {
        for (int ch = 1; ch <= 2; ch++) {
            DAUDIO_AddAudioFormat(creator, 16, 2 * ch, ch, (float)rates[r], DAUDIO_PCM, TRUE, FALSE);
            DAUDIO_AddAudioFormat(creator, 16, 2 * ch, ch, (float)rates[r], DAUDIO_PCM, TRUE, TRUE);
            DAUDIO_AddAudioFormat(creator, 8, ch, ch, (float)rates[r], DAUDIO_PCM, TRUE, FALSE);
            DAUDIO_AddAudioFormat(creator, 8, ch, ch, (float)rates[r], DAUDIO_PCM, FALSE, FALSE);
        }
    }
}

void *DAUDIO_Open(INT32 mixerIndex, INT32 deviceID, int isSource, int encoding, float sampleRate,
                  int sampleSizeInBits, int frameSize, int channels, int isSigned, int isBigEndian,
                  int bufferSizeInBytes) {
    Line *l;
    int slot = -1;

    if (mixerIndex != 0 || !isSource || encoding != DAUDIO_PCM || channels < 1 || channels > 2 ||
        (sampleSizeInBits != 8 && sampleSizeInBits != 16) || sampleRate < 4000 || sampleRate > 96000) {
        ERROR0("PS5 audio: unsupported line format\n");
        return NULL;
    }
    if (!(l = (Line *)calloc(1, sizeof(Line)))) return NULL;
    l->rate = (int)sampleRate;
    l->channels = channels;
    l->bytes = sampleSizeInBits / 8;
    l->isSigned = isSigned;
    l->bigEndian = isBigEndian;
    l->frameSize = l->bytes * channels;
    if (bufferSizeInBytes < l->frameSize * 256) bufferSizeInBytes = l->rate * l->frameSize / 5; /* 200 ms */
    l->size = bufferSizeInBytes - bufferSizeInBytes % l->frameSize;
    l->step = (double)l->rate / OUT_RATE;
    l->phase = 1.0;
    if (!(l->ring = (unsigned char *)malloc(l->size))) {
        free(l);
        return NULL;
    }
    pthread_mutex_lock(&g_lock);
    if (start_output()) {
        for (int n = 0; n < MAX_LINES && slot < 0; n++) if (!g_lines[n]) slot = n;
        if (slot >= 0) g_lines[slot] = l;
    }
    pthread_mutex_unlock(&g_lock);
    if (slot < 0) {
        free(l->ring);
        free(l);
        return NULL;
    }
    return l;
}

int DAUDIO_Start(void *id, int isSource) {
    ((Line *)id)->running = 1;
    return TRUE;
}

int DAUDIO_Stop(void *id, int isSource) {
    ((Line *)id)->running = 0;
    return TRUE;
}

void DAUDIO_Close(void *id, int isSource) {
    Line *l = (Line *)id;

    pthread_mutex_lock(&g_lock);
    for (int n = 0; n < MAX_LINES; n++) if (g_lines[n] == l) g_lines[n] = 0;
    pthread_mutex_unlock(&g_lock);
    free(l->ring);
    free(l);
}

/* Copies what fits and returns how much that was; Java waits and retries. */
int DAUDIO_Write(void *id, char *data, int byteSize) {
    Line *l = (Line *)id;
    int room, n, tail, first;

    pthread_mutex_lock(&g_lock);
    room = l->size - l->fill;
    n = byteSize < room ? byteSize : room;
    n -= n % l->frameSize;
    tail = (l->head + l->fill) % l->size;
    first = n < l->size - tail ? n : l->size - tail;
    memcpy(l->ring + tail, data, first);
    memcpy(l->ring, data + first, n - first);
    l->fill += n;
    pthread_mutex_unlock(&g_lock);
    return n;
}

int DAUDIO_Read(void *id, char *data, int byteSize) {
    return -1; /* no recording */
}

int DAUDIO_GetBufferSize(void *id, int isSource) {
    return ((Line *)id)->size;
}

/* A stopped line is not being played, so it has nothing left to wait for. */
int DAUDIO_StillDraining(void *id, int isSource) {
    Line *l = (Line *)id;
    return l->running && l->fill >= l->frameSize ? TRUE : FALSE;
}

int DAUDIO_Flush(void *id, int isSource) {
    Line *l = (Line *)id;

    pthread_mutex_lock(&g_lock);
    l->fill = 0;
    l->head = 0;
    pthread_mutex_unlock(&g_lock);
    return TRUE;
}

int DAUDIO_GetAvailable(void *id, int isSource) {
    Line *l = (Line *)id;
    int room = l->size - l->fill;
    return room - room % l->frameSize;
}

INT64 DAUDIO_GetBytePosition(void *id, int isSource, INT64 javaBytePos) {
    Line *l = (Line *)id;
    INT64 pos = javaBytePos - l->fill; /* written by Java, minus what is still waiting */
    return pos < 0 ? 0 : pos;
}

void DAUDIO_SetBytePosition(void *id, int isSource, INT64 javaBytePos) {
}

int DAUDIO_RequiresServicing(void *id, int isSource) {
    return FALSE;
}

void DAUDIO_Service(void *id, int isSource) {
}

#endif /* USE_DAUDIO */
