# Third-party components

- **llama.cpp and ggml** — MIT, https://github.com/ggml-org/llama.cpp. Source revision: af50f2f248b475d9760f8e67503d01c0f194f5f4. Native code is linked into the APK. Upstream includes additional third-party notices in its `vendor` directory.
- **SmolVLM-500M-Instruct** — Apache License 2.0, https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct. Quantized files: https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF. Weights are not redistributed in this repository or APK; they are imported by the user.
- **AndroidX ExifInterface** — Apache License 2.0, https://developer.android.com/jetpack/androidx/releases/exifinterface.

The build bundles upstream llama.cpp and vendor license files in APK assets. Model license: https://www.apache.org/licenses/LICENSE-2.0.
