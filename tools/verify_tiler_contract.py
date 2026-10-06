"""Validate the fixed 256px QNN tiler's central-crop contract.

Run without a local benchmark:
  uv run --with onnxruntime python tools/verify_tiler_contract.py PATH/TO/model.onnx
"""

import argparse
import os

os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import numpy as np
import onnxruntime as ort

TILE = 256
SCALE = 2
HALO = 16
CORE = TILE - 2 * HALO
OUTPUT_TILE = TILE * SCALE
OUTPUT_HALO = HALO * SCALE


def run_tile(session: ort.InferenceSession, image: np.ndarray, origin_x: int, origin_y: int) -> np.ndarray:
    """Run one zero-padded NCHW tile at its nominal source-image origin."""
    patch = np.zeros((1, 3, TILE, TILE), dtype=np.float32)
    for tile_y in range(TILE):
        source_y = origin_y + tile_y
        if not 0 <= source_y < image.shape[0]:
            continue
        for tile_x in range(TILE):
            source_x = origin_x + tile_x
            if 0 <= source_x < image.shape[1]:
                patch[0, :, tile_y, tile_x] = image[source_y, source_x]
    return session.run(None, {"input": patch})[0][0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    args = parser.parse_args()

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(args.model, options, providers=["CPUExecutionProvider"])
    input_shape = session.get_inputs()[0].shape
    output_shape = session.get_outputs()[0].shape
    assert input_shape == [1, 3, TILE, TILE], input_shape
    assert output_shape == [1, 3, OUTPUT_TILE, OUTPUT_TILE], output_shape

    image = np.random.default_rng(991).random((800, 900, 3), dtype=np.float32)
    # Production tiles start at -HALO and CORE-HALO. A centered reference tile
    # proves both central crops are equivalent where they meet.
    left = run_tile(session, image, -HALO, 200)
    right = run_tile(session, image, CORE - HALO, 200)
    reference = run_tile(session, image, CORE // 2 - HALO, 200)

    left_diff = np.max(np.abs(left[:, :, 256:480] - reference[:, :, 32:256]))
    right_diff = np.max(np.abs(right[:, :, 32:256] - reference[:, :, 256:480]))

    top = run_tile(session, image, 200, -HALO)
    bottom = run_tile(session, image, 200, CORE - HALO)
    reference_vertical = run_tile(session, image, 200, CORE // 2 - HALO)
    top_diff = np.max(np.abs(top[:, 256:480, :] - reference_vertical[:, 32:256, :]))
    bottom_diff = np.max(np.abs(bottom[:, 32:256, :] - reference_vertical[:, 256:480, :]))

    maximum = float(max(left_diff, right_diff, top_diff, bottom_diff))
    print(f"input={input_shape} output={output_shape} halo={HALO} core={CORE} max_diff={maximum}")
    assert maximum < 1e-5, maximum


if __name__ == "__main__":
    main()
