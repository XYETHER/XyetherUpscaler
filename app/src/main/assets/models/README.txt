# Bundled model assets

This directory contains 16 grouped INT8 DLC files for the eight visible model profiles. See tools/model-assets.json for exact catalog mappings, quantization metadata, sizes and SHA256 hashes.

Inputs: RGB NCHW [batch,3,256,256]. Outputs: RGB NCHW [batch,3,512,512]. Static batch is 8 for Quality/Balanced and 16 for Speed. The portable MNN graphs live in ../mnn and have separate precision/runtime contracts.

Xyether states these weights were made by Xyether and releases them under Apache-2.0. No datasets, training credentials or local paths are included.
