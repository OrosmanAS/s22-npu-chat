# S22+ NPU Chat

An Android app (Kotlin, Jetpack Compose) that runs a local LLM on the Hexagon v69 NPU of a Galaxy S22+ (Snapdragon 8 Gen 1 / SM8450). Everything runs on the phone.

- Model: [`assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU`](https://huggingface.co/assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU)
- Runtime: `litertlm-android` 0.17.1 + QAIRT 2.50.0.260828 + `libLiteRtDispatch_Qualcomm.so` from LiteRT `9fe5be4`
- Build: GitHub Actions only, so nothing is built on the PC

See [npu-chat-handoff.md](npu-chat-handoff.md) for the full spec.

> This repo must stay **private**. Qualcomm QNN libraries may not be redistributed publicly.

## Getting the APK

Every push runs **Build APK** (`.github/workflows/build.yml`). Pushes to `main` also replace the
**Latest build** pre-release, which has the APK attached. You can download it on the phone while
signed in to GitHub. Every other run attaches the APK as a workflow artifact (`npu-chat-apk`).

All builds use the same committed debug key (`app/debug.p12`), so a new APK installs over the old one
and keeps the downloaded model. Uninstalling deletes the model.

## How the build works

1. **native-libs job**
   - Fetches the QAIRT 2.50 headers and the four v69 runtime libs from Qualcomm's public SDK zip.
     It uses range requests (`scripts/fetch_qairt.py`), so only about 20 MB of the 2.6 GB zip is downloaded.
   - Builds `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` with Bazel inside a
     LiteRT-LM v0.17.1 checkout, which pins LiteRT `9fe5be4`, against those headers.
   - Checks that the dispatch lib does not depend on `libLiteRt.so`. The AAR links LiteRT
     statically into `liblitertlm_jni.so`.
   - Caches the result. Later runs skip Bazel entirely.
2. **apk job**
   - Copies the libs into `app/src/main/jniLibs/arm64-v8a/` together with a provenance file (versions and SHA-256s).
   - Runs Gradle `assembleDebug`.
   - Checks that every required lib is inside the APK.

To force a rebuild of the native libs, run the workflow manually with "rebuild_native", or bump `NATIVE_RECIPE`.

## App

- **Chat**:
  - Streams the answer as it is generated.
  - Has a thinking toggle. The reasoning appears in a collapsible section.
  - Shows tok/s (exact numbers from LiteRT-LM's benchmark info) and context usage.
- **First launch**:
  - Downloads the model as a resumable foreground job, or imports a `.litertlm` from storage.
  - Checks the SHA-256 before the model is used.
- **NPU tab**: shows every init step with its evidence:
  - device and SoC
  - the `ADSP_LIBRARY_PATH` that is set
  - the bundled libs and their build provenance
  - the FastRPC check
  - `dlopen` of each QNN and dispatch lib
  - model file and free memory
  - the engine and conversation creation times
  - the reasons previous app processes ended, including native crashes
- **Logs tab**:
  - A live log viewer with level and text filters.
  - **Share** or **Save** bundles device info, init steps, previous crashes, the app log, and this
    app's own logcat, which includes native LiteRT/QNN lines. You can send logs without a PC.
- **Settings**:
  - model variant (context 4096 or 16384)
  - Conversation API or raw-session mode
  - system prompt and max output tokens
  - log levels and native verbose logs
  - auto-init and lib preloading

### Notes

- The NPU path is greedy, so there are no sampling settings.
- The NPU can be initialised only once per process. "Restart app" starts a fresh process.
- If NPU init kills the process, the next launch detects this and skips auto-init.
