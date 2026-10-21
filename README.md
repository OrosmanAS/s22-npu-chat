# S22+ NPU Chat

An Android app that runs a local LLM on the **Hexagon NPU of the Snapdragon 8 Gen 1** (SM8450, Hexagon v69). It was built and tested on a Samsung Galaxy S22+. The model runs entirely on the phone, with no server and no internet once it's downloaded.

> [!WARNING]
> **Disclaimer: please read.**
>
> - **Made with a lot of help from AI.** Most of the code, the build pipeline and this README were written together with an AI coding assistant (Claude). I tested it on one phone and steered it and fixed it dumb code. It was not written or reviewed by an Android or ML expert.
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

Pick **one** of the two ways below to make your private copy.

### Option A: only a web browser

1. Sign in to GitHub and open **https://github.com/new/import**.
2. Fill in the form:
   - **Your old repository's clone URL:** `https://github.com/OrosmanAS/s22-npu-chat.git`
   - **Repository name:** anything, for example `my-npu-chat`
   - **Privacy:** select **Private**. This is required: the full build only runs in private repos.
3. Click **Begin import** and wait for "Your new repository is ready" (about a minute).
4. Open your new repository, go to the **Actions** tab, and if GitHub asks, click **"I understand my workflows, go ahead and enable them"**.
5. In the left sidebar click **Build APK**, then **Run workflow** (on the right). Keep **Branch: main** and click the green **Run workflow** button.
6. Wait until the run shows a green tick. Click it to watch the progress of each job.
7. Go to your repository's **Releases** (right sidebar, or `https://github.com/YOUR-NAME/my-npu-chat/releases`). **Latest build** has the APK, `npu-chat-<number>.apk`.

Continue with [Install it on the phone](#install-it-on-the-phone).

### Option B: command line

You need [Git](https://git-scm.com/downloads) and the [GitHub CLI](https://cli.github.com/) (`gh`). Replace `YOUR-NAME` with your GitHub username.

1. Sign in to GitHub from the terminal:

   ```bash
   gh auth login
   ```

2. Create an empty **private** repository:

   ```bash
   gh repo create my-npu-chat --private
   ```

3. Copy this repository into it. Pushing to `main` starts the build automatically:

   ```bash
   git clone --bare https://github.com/OrosmanAS/s22-npu-chat.git
   cd s22-npu-chat.git
   git push --mirror https://github.com/YOUR-NAME/my-npu-chat.git
   cd ..
   ```

   Then delete the temporary `s22-npu-chat.git` folder. Use `rm -rf s22-npu-chat.git` on Linux/macOS, or `Remove-Item -Recurse -Force s22-npu-chat.git` in PowerShell.
4. Watch the build until it finishes:

   ```bash
   gh run watch --repo YOUR-NAME/my-npu-chat
   ```

   If it says no run is in progress, start one by hand:

   ```bash
   gh workflow run build.yml --repo YOUR-NAME/my-npu-chat
   ```

   Then run `gh run watch` again.
5. Download the APK:

   ```bash
   gh release download latest --repo YOUR-NAME/my-npu-chat --pattern "*.apk"
   ```

### Optional: a signing key, so updates keep your models

Without a key, every build is signed with GitHub's throwaway debug key. Installing a newer build then fails with *"App not installed"*. You'd have to uninstall first, which **deletes the downloaded models** (2–23 GB). With your own key, every build installs as an update.

It's best to do this **before** the first build, or rebuild once after setting it:

1. Create a key. You need a Java JDK for `keytool`; [Temurin](https://adoptium.net/) works. Choose a password when asked and keep it safe:

   ```bash
   keytool -genkeypair -keystore npuchat.p12 -storetype PKCS12 -alias npuchat -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=NPU Chat"
   ```

   The alias must be `npuchat`, because the build expects it.
2. Convert it to base64:
   - Linux:

     ```bash
     base64 -w0 npuchat.p12 > npuchat.p12.b64
     ```

   - macOS:

     ```bash
     base64 -i npuchat.p12 -o npuchat.p12.b64
     ```

   - Windows PowerShell:

     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("npuchat.p12")) | Set-Content -NoNewline npuchat.p12.b64
     ```

3. Store both values as repository secrets.
   - **On the web:** go to your repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**. Add `NPUCHAT_KEYSTORE_B64` with the contents of `npuchat.p12.b64`, and `NPUCHAT_KEYSTORE_PASSWORD` with your password.
   - **With the CLI:**

     ```bash
     gh secret set NPUCHAT_KEYSTORE_B64 --repo YOUR-NAME/my-npu-chat < npuchat.p12.b64
     ```

     ```bash
     gh secret set NPUCHAT_KEYSTORE_PASSWORD --repo YOUR-NAME/my-npu-chat
     ```

     The second command asks for the password.
4. Run the build again (Actions → Build APK → Run workflow, or `gh workflow run build.yml --repo YOUR-NAME/my-npu-chat`).
5. Keep `npuchat.p12` and its password somewhere safe. **Never commit them;** `*.p12` is already in `.gitignore`.

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
| Only "Compile check" runs, there's no APK | The repo is public. The full build only runs in a **private** repository. |
| No workflow runs at all | Actions → enable workflows, then Build APK → Run workflow. |
| *"App not installed"* when updating | The new APK is signed with a different key. Set up a [signing key](#optional-a-signing-key-so-updates-keep-your-models), then uninstall once. This deletes the models. |
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
