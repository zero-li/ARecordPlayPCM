#ifndef DECIMATE6_H_
#define DECIMATE6_H_

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DECIMATE6_TAPS 96
#define DECIMATE6_FACTOR 6

typedef struct {
    float history[DECIMATE6_TAPS];
    size_t index;
    size_t phase;
} Decimate6State;

void decimate6_init(Decimate6State *state);
void decimate6_reset(Decimate6State *state);
size_t decimate6_process(Decimate6State *state, const float *input, size_t input_len, float *output);

#ifdef __cplusplus
}
#endif

#endif  // DECIMATE6_H_
