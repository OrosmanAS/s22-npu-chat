# S22+ NPU Chat

An Android app (Kotlin, Jetpack Compose) that runs a local LLM on the Hexagon v69 NPU of a Galaxy S22+ (Snapdragon 8 Gen 1 / SM8450). Everything runs on the phone.

- Model: [`assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU`](https://huggingface.co/assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU)
- Runtime: LiteRT-LM v0.17.1 + QAIRT 2.50 + `libLiteRtDispatch_Qualcomm.so` from LiteRT `9fe5be4`
- Build: GitHub Actions only, with the debug APK uploaded as a workflow artifact

See [npu-chat-handoff.md](npu-chat-handoff.md) for the full spec, runtime requirements and build plan.

> This repo must stay **private**. Qualcomm QNN libraries may not be redistributed publicly.
