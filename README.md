# S22+ NPU Chat

An Android app that runs a local LLM on the **Hexagon NPU of the Snapdragon 8 Gen 1** (SM8450, Hexagon v69). It was built and tested on a Samsung Galaxy S22+. The model runs entirely on the phone, with no server and no internet once it's downloaded.

> [!WARNING]
> **Disclaimer: please read.**
>
> - **Made with a lot of help from AI.** Most of the code, the build pipeline and this README were written together with an AI coding assistant (Claude). I tested it on one phone and steered it and fixed its dumb code. It was not written or reviewed by an Android or ML expert.
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

**There is no public APK, and this public repo can't build one.** The app needs Qualcomm's QNN runtime libraries (QAIRT), and those are Qualcomm's to distribute, not this project's. The full build only runs in a **private** repository, so you build your own copy with your own free GitHub account. Nothing has to be built on your PC.

What the build does, in [`.github/workflows/build.yml`](.github/workflows/build.yml):
1. It downloads the QAIRT headers and the four Hexagon v69 runtime libraries straight from Qualcomm's public SDK URL. [`scripts/fetch_qairt.py`](scripts/fetch_qairt.py) uses range requests, so only ~20 MB of the 2.6 GB zip is fetched.
2. It builds `libLiteRtDispatch_Qualcomm.so` with Bazel from LiteRT-LM v0.17.1.
3. It builds `llama-server` for arm64 Android at a pinned llama.cpp commit.
4. It assembles the APK and publishes it as a **"Latest build"** pre-release in *your* private repo.

### Before you start

- **A phone with a Snapdragon 8 Gen 1 (SM8450).** For example:
  - Galaxy S22, S22+ and S22 Ultra (Snapdragon versions)
  - Xiaomi 12 / 12 Pro
  - OnePlus 10 Pro
  - Nubia Red Magic 7 / 7 Pro

  Other chips fail at the NPU step with *QNN error 1002*. The 8+ Gen 1 (SM8475) is untested. To check your chip, use either:
  - a free app like CPU-Z (look for **SM8450**), or
  - with USB debugging on, run `adb shell getprop ro.soc.model`, which should print `SM8450`.
- **A free GitHub account.** Private repositories and Actions are included. The first build takes roughly 15–25 minutes of Actions time, later builds about 5. A free account includes 2,000 minutes a month for private repos.
- **About 3 GB of free storage** on the phone for the default model, and Wi-Fi for the download.

### Build it

You need [Git](https://git-scm.com/downloads) and the [GitHub CLI](https://cli.github.com/) (`gh`). The commands work in a Linux/macOS terminal and in Windows PowerShell; where they differ, both versions are given. Replace `YOUR-NAME` with your GitHub username.

#### 1. Create your private copy

1. Sign in to GitHub from the terminal:

   ```bash
   gh auth login
   ```

2. Create an empty **private** repository. It must be private: the full build doesn't run in public repos.

   ```bash
   gh repo create my-npu-chat --private
   ```

#### 2. Optional: a signing key, so updates keep your models

Without a key, every build is signed with GitHub's throwaway debug key. Installing a newer build then fails with *"App not installed"*. You'd have to uninstall first, which **deletes the downloaded models** (2–23 GB). With your own key, every build installs as an update. Do this now, before the first build. You can skip it and come back later, but then you'll need one uninstall.

1. Create a key. You need a Java JDK for `keytool`; [Temurin](https://adoptium.net/) works. Choose a password when asked and keep it safe. The alias must be `npuchat`, because the build expects it.

   ```bash
   keytool -genkeypair -keystore npuchat.p12 -storetype PKCS12 -alias npuchat -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=NPU Chat"
   ```

2. Store the key as a repository secret.
   - Linux:

     ```bash
     base64 -w0 npuchat.p12 | gh secret set NPUCHAT_KEYSTORE_B64 --repo YOUR-NAME/my-npu-chat
     ```

   - macOS:

     ```bash
     base64 -i npuchat.p12 | gh secret set NPUCHAT_KEYSTORE_B64 --repo YOUR-NAME/my-npu-chat
     ```

   - Windows PowerShell:

     ```powershell
     gh secret set NPUCHAT_KEYSTORE_B64 --repo YOUR-NAME/my-npu-chat --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\npuchat.p12")))
     ```

3. Store the password as a second secret. The command asks for it:

   ```bash
   gh secret set NPUCHAT_KEYSTORE_PASSWORD --repo YOUR-NAME/my-npu-chat
   ```

4. Check that both secrets are there:

   ```bash
   gh secret list --repo YOUR-NAME/my-npu-chat
   ```

5. Keep `npuchat.p12` and its password somewhere safe; you need the same key for every future build. **Never commit them;** `*.p12` is already in `.gitignore`.

#### 3. Copy the code in and build

1. Make a temporary copy of this repository:

   ```bash
   git clone --bare https://github.com/OrosmanAS/s22-npu-chat.git
   ```

2. Go into it:

   ```bash
   cd s22-npu-chat.git
   ```

3. Push it to your private repository. Pushing to `main` starts the build automatically:

   ```bash
   git push --mirror https://github.com/YOUR-NAME/my-npu-chat.git
   ```

4. Leave the temporary copy and delete it.
   - Linux/macOS:

     ```bash
     cd .. && rm -rf s22-npu-chat.git
     ```

   - Windows PowerShell:

     ```powershell
     cd ..; Remove-Item -Recurse -Force s22-npu-chat.git
     ```

5. Watch the build. The first one takes roughly 15–25 minutes, later ones about 5:

   ```bash
   gh run watch --repo YOUR-NAME/my-npu-chat
   ```

   If it says nothing is running, start a build by hand, then run the `gh run watch` command again:

   ```bash
   gh workflow run build.yml --repo YOUR-NAME/my-npu-chat
   ```

6. When it's done, download the APK into the current folder:

   ```bash
   gh release download latest --repo YOUR-NAME/my-npu-chat --pattern "*.apk"
   ```

   The build also publishes it as **Latest build** under your repository's **Releases**, which is handy for downloading straight on the phone.

To rebuild later, for example after adding the signing key, run `gh workflow run build.yml --repo YOUR-NAME/my-npu-chat`.

### Install it on the phone

**Straight on the phone, no PC:**
1. Open your repository's **Releases** page in the phone's browser while **signed in to GitHub**. Release files of a private repo need you to be signed in.
2. Tap **Latest build**, then the `.apk` file to download it.
3. Open the download. If Android asks, allow your browser to **"Install unknown apps"**, then tap **Install**.

**With a PC over USB:**
1. On the phone, open **Settings → About phone → Software information** and tap **Build number** seven times. That enables Developer options.
2. Go to **Settings → Developer options** and turn on **USB debugging**.
3. Connect the phone and accept the **"Allow USB debugging?"** prompt.
4. Install with [adb (Android platform-tools)](https://developer.android.com/tools/releases/platform-tools):

   ```bash
   adb install -r npu-chat-<number>.apk
   ```

   `-r` replaces an existing install and keeps its data.

### First run

1. Open **NPU Chat** and allow notifications when asked; the model download runs in the background.
2. On the **Chat** tab, pick a model. **Qwen3.5-2B** (2.16 GB, ~16 tok/s) is the best first test. Tap **Download** and stay on Wi-Fi. The file is checked with SHA-256 when it finishes.
3. The NPU starts automatically after the download, which takes about 5–15 seconds. The **NPU** tab shows each step turning green.
4. Chat! Keep **Thinking** off for quick answers; with it on, the model reasons for up to about a minute first.
5. If something fails, open **Logs → Share logs**. That bundle has everything needed to debug it.

Other models are under **Settings → Models**. Switching models restarts the app. The CPU models marked *experimental* can freeze the phone until it restarts itself, and the app asks before loading them.

### Updating later

New changes here don't reach your copy by themselves. To pull them in:

1. Clone your copy:

   ```bash
   git clone https://github.com/YOUR-NAME/my-npu-chat.git
   ```

2. Go into the folder:

   ```bash
   cd my-npu-chat
   ```

3. Add this repository as `upstream`:

   ```bash
   git remote add upstream https://github.com/OrosmanAS/s22-npu-chat.git
   ```

4. Pull in the changes:

   ```bash
   git pull upstream main
   ```

5. Push them to your copy:

   ```bash
   git push origin main
   ```

The push starts a new build; install the new APK the same way as before. Only steps 4 and 5 are needed next time.

### Troubleshooting

| Problem | Fix |
|---|---|
| Only "Compile check" runs, there's no APK | The repo is public; check with `gh repo view YOUR-NAME/my-npu-chat --json visibility`. The full build only runs in a **private** repository. |
| No workflow runs at all | Start one with `gh workflow run build.yml --repo YOUR-NAME/my-npu-chat`, then check with `gh run list --repo YOUR-NAME/my-npu-chat`. |
| *"App not installed"* when updating | The new APK is signed with a different key. Set up a [signing key](#2-optional-a-signing-key-so-updates-keep-your-models), then uninstall once. This deletes the models. |
| NPU step fails with **1002** | The phone isn't SM8450, or another app is using the NPU's memory. Close heavy apps and use **Restart app**. |
| A reply loops forever | Lower the thinking budget or turn Thinking off, under Settings → Stop repetition loops. |
| The phone freezes with Kimi-Linear | Expected risk: it's much bigger than the RAM. The phone restarts itself and the app won't auto-load it again. |

## Credits and licences

- This project: [Apache-2.0](LICENSE).
- [LiteRT](https://github.com/google-ai-edge/LiteRT) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google, Apache-2.0).
- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT).
- Qualcomm AI Engine Direct / QAIRT: Qualcomm's licence. Not included here.
- Models belong to their authors under their own licences: Qwen, Gemma, Granite, LFM (LFM Open License), Kimi-Linear. The SM8450 NPU builds are by [assassinshadow](https://huggingface.co/assassinshadow).
- [`docs/handoff.md`](docs/handoff.md) is the original project brief this was built from.

Not affiliated with Samsung, Qualcomm, Google, or any model author.
