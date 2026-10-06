# Handoff: NPU chat app for Galaxy S22+ (Snapdragon 8 Gen 1)

Paste this whole file as the first message of a Claude Code cloud session attached to the private repo.

## Goal
A proper Android app (Kotlin) that runs a local LLM on the Hexagon NPU of my Galaxy S22+, fully on the phone, no PC needed. Built entirely in GitHub Actions; I install the APK on the phone. I have no WSL and a 16 GB Windows PC, so nothing should need building locally.

## Hardware
- Samsung Galaxy S22+, Snapdragon 8 Gen 1 (SM8450), Hexagon NPU **v69**, 8 GB RAM.
- Most official NPU LLM tooling (llama.cpp Hexagon backend, Qualcomm AI Hub LLM recipes, Google's published LiteRT-LM bundles, AI Edge Gallery NPU path) targets v73+ (8 Gen 2 and newer) and does not support this phone.

## Model (decided)
`assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU` on Hugging Face — https://huggingface.co/assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU
- Compiled for SM8450 / Hexagon v69, built and measured on a Galaxy S22 Ultra. Apache-2.0.
- Files: `qwen35-2b_sm8450.litertlm` (2.16 GB, ctx 4096, ~16 tok/s) and `qwen35-2b-ctx16384_sm8450.litertlm` (2.16 GB, ctx 16384, ~8 tok/s). Use the 4096 one by default.
- SHA-256 (4096): `3dcd348bbabf7f6c103ad49050d69a7644f26a4f7ad9f03c7b1226633daf2a0f`
- Text only. Thinking mode works (`<think>…</think>`). Stop token `<|im_end|>`.
- Why not a 4B model: v69 has no prefill/decode weight sharing and one process can map only ~1.85 GB on the cDSP; the 2B build already fills that.

### Runtime requirements from the model card
1. **QAIRT 2.50 runtime** (must be 2.50; 2.39 fails with a QnnSystem version mismatch): `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV69Stub.so` from `lib/aarch64-android/`, and `libQnnHtpV69Skel.so` from `lib/hexagon-v69/unsigned/`.
2. **`libLiteRtDispatch_Qualcomm.so`** built from the LiteRT commit that LiteRT-LM v0.17.1 embeds: LiteRT **`9fe5be4`**. LiteRT 2.2.0's dispatch lib crashes LiteRT-LM v0.17.1 on model unload. Bazel target: `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` (NDK r28b+).
3. LiteRT-LM **v0.17.1** runtime.

### Runtime quirks from the model card
- NPU executor is greedy: temperature/top-k/seed have no effect.
- One NPU model per process; switching models needs a process restart.
- Prompt tokens go in one per call, so prefill speed = decode speed (~16 tok/s). Long prompts are slow.
- Uses most of the DSP's per-process memory budget; close heavy apps.
- On some Samsung builds a foreground/background app is scheduled on little cores (their CLI numbers used `taskset f0`).

## App approach
Use the LiteRT-LM Kotlin API: `com.google.ai.edge.litertlm:litertlm-android` (Google Maven), version matching **0.17.1** if published (verify; if not, pick the closest and rebuild the dispatch lib from the LiteRT commit that AAR embeds). `EngineConfig(modelPath, backend = Backend.NPU(nativeLibraryDir = ...), ...)`.

Reference: "Six silent failures" write-up (LiteRT-LM NPU in an Android app on S25 Ultra): https://dev.to/jdshah/what-i-learned-getting-an-npu-to-actually-initialize-six-silent-failures-oa1 — lessons to apply:
1. Dispatch lib must be ABI-matched to the AAR's `libLiteRt.so`; a mismatch fails silently as "unresolved custom op: DISPATCH_OP". Don't use the phone's /vendor QNN libs.
2. The AAR ships only `libLiteRt.so`, `libLiteRtClGlAccelerator.so`, `liblitertlm_jni.so`; bundle the QNN + dispatch libs in `jniLibs/arm64-v8a/`. Don't bundle the compiler plugin.
3. Set `ADSP_LIBRARY_PATH` (and `LD_LIBRARY_PATH`) in a custom `Application.onCreate()`, **before** any QNN lib is loaded, with `nativeLibraryDir` first (so our QAIRT 2.50 v69 skel wins) plus vendor DSP paths. Error 1002 = DSP can't find/load the skel.
4. Keep vision/audio sub-backends on CPU (GPU sub-backend crashed engine init on Android 16).
5. Constrained decoding off; `samplerConfig = null` on NPU.
6. NPU can't be re-initialised in the same process after release; restart the process.
- `useLegacyPackaging = true` (dispatch lookup readdirs `nativeLibraryDir`).

## Build plan (GitHub Actions, ubuntu runner)
1. Download QAIRT 2.50 (check whether LiteRT's Bazel workspace fetches QAIRT from a public URL; reuse that) and extract the four v69 libs.
2. Build `libLiteRtDispatch_Qualcomm.so` with Bazel from LiteRT `9fe5be4` (or the commit matching the chosen AAR). Cache Bazel outputs. Watch runner RAM (private-repo runners are small): limit jobs.
3. Copy libs into `app/src/main/jniLibs/arm64-v8a/`, build a debug APK with Gradle, upload as a workflow artifact.
- Repo must stay **private** (Qualcomm libs aren't for public redistribution).

## App features (v1)
- First launch: download the model from Hugging Face with progress, verify SHA-256, store in app files dir. Allow import from local storage as an alternative.
- Chat UI (Jetpack Compose), streaming output, thinking toggle, collapsible thinking section, tok/s display.
- Backend status screen showing the NPU init steps.
- **Debug with evidence:** detailed, tagged logcat logging of every init step (env vars, lib dlopen, QNN context creation, errors), plus an in-app log viewer/export so I can send logs without a PC. Keep the logging after fixes, just quieter (log levels), don't delete it.

## Open risks
- Exact `litertlm-android` Maven version vs the model's LiteRT-LM v0.17.1 — verify first.
- Samsung little-core scheduling may cut in-app speed below 16 tok/s.
- Unofficial community model build; small quality checks only.
