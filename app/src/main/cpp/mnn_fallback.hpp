#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

bool mnnFallbackIsLoaded();
std::string mnnFallbackLoad(const std::string& modelPath);
std::string mnnFallbackUpscale(const uint8_t* input, int width, int height, uint8_t* output,
                               size_t inputCapacity, size_t outputCapacity);
void mnnFallbackRelease();

// Live tile-batch progress for UI polling: 0-100 while an upscale runs, else 0.
int mnnFallbackProgressPercent();
bool mnnFallbackProgressActive();
