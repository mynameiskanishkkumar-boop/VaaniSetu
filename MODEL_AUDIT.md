# VaaniSetu 10-Language Model Audit & Verification Matrix
**Smart India Hackathon 2026 | Problem Statement ID: 26173 | ISRO**

---

## Model Verification Table

| # | Language | ISO Code | STT Model | STT License | STT Status | TTS Model | TTS License | TTS Status | Memory Strategy |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **Hindi** | `hi` | Sherpa IndicZipformer INT8 | Apache 2.0 | **VERIFIED** | Sherpa VITS Hindi INT8 | Apache 2.0 | **VERIFIED** | Single active model in RAM |
| 2 | **English** | `en` | Sherpa Zipformer-en INT8 | Apache 2.0 | **VERIFIED** | Piper VITS en-US INT8 | MIT | **VERIFIED** | Single active model in RAM |
| 3 | **Bengali** | `bn` | Sherpa Zipformer-bn INT8 | Apache 2.0 | **VERIFIED** | Sherpa VITS Bengali INT8 | Apache 2.0 | **VERIFIED** | Single active model in RAM |
| 4 | **Tamil** | `ta` | Sherpa Zipformer-ta INT8 | Apache 2.0 | **VERIFIED** | Sherpa VITS Tamil INT8 | Apache 2.0 | **VERIFIED** | Single active model in RAM |
| 5 | **Marathi** | `mr` | Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| IndicTTS Marathi / eSpeak | MIT / GPLv3 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |
| 6 | **Telugu** | `te` | Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| Sherpa VITS Telugu INT8 | Apache 2.0 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |
| 7 | **Gujarati**| `gu` | Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| IndicTTS Gujarati / eSpeak | MIT / GPLv3 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |
| 8 | **Kannada** | `kn` | Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| IndicTTS Kannada / eSpeak | MIT / GPLv3 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |
| 9 | **Malayalam**| `ml`| Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| Sherpa VITS Malayalam INT8 | Apache 2.0 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |
| 10| **Odia** | `or` | Whisper-Tiny INT8 | MIT | **NEEDS VALIDATION**| IndicTTS Odia / eSpeak | MIT / GPLv3 | **NEEDS VALIDATION**| Dynamic JNI unload on switch |

---

## Runtime Specifications

- **Edge Runtime:** Sherpa-ONNX (Native C++ via JNI)
- **Quantization:** INT8 quantized weights
- **RAM Optimization:** Strict single-active-model policy (unloads prior model and triggers GC on language selection)
- **Core TTS Engine:** Sherpa-ONNX VITS / Piper ONNX (No proprietary system TTS dependencies)
