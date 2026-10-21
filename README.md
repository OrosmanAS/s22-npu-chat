# S22+ NPU Chat

An Android app that runs a local LLM on the **Hexagon NPU of the Snapdragon 8 Gen 1** (SM8450, Hexagon v69). It was built and tested on a Samsung Galaxy S22+. The model runs entirely on the phone, with no server and no internet once it's downloaded.

> [!WARNING]
> **Disclaimer: please read.**
>
> - **Made with a lot of help from AI.** Most of the code, the build pipeline and this README were written together with an AI coding assistant (Claude). A human tested it on one phone and steered it. It was not written or reviewed by an Android or ML expert.
> - **It is not good software.** It is not polished, optimised, secure, or "the best it can be" in any way. Expect bugs, rough edges and hacks.
> - **Don't trust it.** Don't rely on it for anything important, don't trust what the models say, and don't use it with sensitive data. Some models in it can freeze the phone until it restarts itself (they're marked experimental and ask first).
> - **Why it exists:** to show that an LLM *can* run on the Snapdragon 8 Gen 1 NPU, which most official tooling no longer supports. That's it.
>
> Provided as-is, with no warranty of any kind (see [LICENSE](LICENSE)).

## What it does

- **NPU models (Hexagon v69 through LiteRT-LM).**
  - Every model listed here comes from [assassinshadow](https://huggingface.co/assassinshadow), who compiled it for SM8450:

    | Model | Speed on the S22+ |
    |---|---|
    | Qwen3.5-2B, context 4096 | ~16 tok/s (measured) |
    | Qwen3.5-2B, context 16384 | ~8 tok/s |
    | Gemma 4 E2B-it (text) | 15–18 tok/s |
    | Granite 4.2 3B, context 4096 | ~8 tok/s |
    | Granite 4.2 3B, context 2048 | ~10 tok/s |

    Only the first figure was measured on the S22+; the rest are from the model cards.
  - The NPU decodes greedily (no temperature), so the app adds anti-loop limits: a thinking budget, a repetition penalty and blocking of repeated phrases.
- **CPU models (bundled llama.cpp server).**
  - The models are mixture-of-experts GGUFs, read from storage with mmap, so a model can be bigger than the phone's RAM:
    - [LFM2.5 8B-A1B](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B-GGUF)
    - [Kimi-Linear 48B-A3B](https://huggingface.co/bartowski/moonshotai_Kimi-Linear-48B-A3B-Instruct-GGUF), *experimental*
  - Kimi-Linear 48B ran at **~1 tok/s** on an 8 GB phone.
- **Debugging with evidence.**
  - An NPU/CPU tab shows every load step: the DSP environment, the bundled libraries, each `dlopen`, memory, and the timings.
  - A live strip shows tok/s, NPU temperature, RAM, storage, disk reads, CPU and GPU.
  - A log viewer can share or save a bundle with the app's own logcat and any previous crash reports.
- **Models** are downloaded from Hugging Face (resumable, SHA-256 checked) or imported from storage. Switching models restarts the app, because the NPU allows one model per process.

There's **no NPU utilisation percentage**. Android blocks apps from reading the Hexagon counters (SELinux), so the app shows throughput and the NPU's temperature sensors instead.

## Why this is hard on the 8 Gen 1

Most NPU LLM tooling targets Hexagon **v73+** (Snapdragon 8 Gen 2 and newer), and v69 has tighter limits:
- no weight sharing between prompt and generation graphs
- about 1.85 GB of DSP memory per process
- prompts are read one token per call, so reading is as slow as writing

The models above were compiled specifically for v69. This app pairs them with matching runtimes:
- **LiteRT-LM** `litertlm-android` 0.17.1
- **QAIRT** 2.50.0.260828
- a **LiteRT dispatch library** built from LiteRT `9fe5be4`, the commit LiteRT-LM 0.17.1 pins

## Getting the app

**There is no public APK.** The app needs Qualcomm's QNN runtime libraries (QAIRT), and those are Qualcomm's to distribute, not this project's. This public repo only runs a compile check.

To build it yourself, copy this repo into a **private** repository you own. A private fork isn't possible; use "Import repository" or push a clone. GitHub Actions then runs the full pipeline in [`.github/workflows/build.yml`](.github/workflows/build.yml):

1. Download the QAIRT headers and the four v69 runtime libraries straight from Qualcomm's public SDK URL ([`scripts/fetch_qairt.py`](scripts/fetch_qairt.py) uses range requests, so only ~20 MB of the 2.6 GB zip is fetched).
2. Build `libLiteRtDispatch_Qualcomm.so` with Bazel from LiteRT-LM v0.17.1.
3. Build `llama-server` for arm64 Android at a pinned llama.cpp commit.
4. Assemble a debug APK and publish it as a pre-release in *your* private repo.

Optional: set the secrets `NPUCHAT_KEYSTORE_B64` (a base64 PKCS#12 keystore with alias `npuchat`) and `NPUCHAT_KEYSTORE_PASSWORD`. Then every build is signed with the same key, and updates install without wiping the downloaded models.

Nothing has to be built on your own PC. It only works on Snapdragon 8 Gen 1 (SM8450) phones; anything else fails at the NPU step with QNN error 1002.

## Credits and licences

- This project: [Apache-2.0](LICENSE).
- [LiteRT](https://github.com/google-ai-edge/LiteRT) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google, Apache-2.0).
- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT).
- Qualcomm AI Engine Direct / QAIRT: Qualcomm's licence. Not included here.
- Models belong to their authors under their own licences: Qwen, Gemma, Granite, LFM (LFM Open License), Kimi-Linear. The SM8450 NPU builds are by [assassinshadow](https://huggingface.co/assassinshadow).
- [`docs/handoff.md`](docs/handoff.md) is the original project brief this was built from.

Not affiliated with Samsung, Qualcomm, Google, or any model author.
