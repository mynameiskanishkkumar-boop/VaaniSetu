# VaaniSetu (वाणीसेतु) — Offline Multilingual Voice Transceiver
**Smart India Hackathon 2026 | Problem Statement ID: 26173 | ISRO**

> *"Speak. Convert. Connect. Listen."*

---

## 🌟 Overview
**VaaniSetu** is an offline, edge-AI multilingual voice transceiver engineered for low-data-rate communication links (tactical mesh, disaster response, VHF/UHF, satellite uplinks).

By decoupling speech capture from raw audio transmission, VaaniSetu converts captured voice to text on-device using local Speech-to-Text (STT), transmits ultra-compact framed binary packets (< 100 bytes) over local device-to-device (D2D) networks (Wi-Fi Hotspot / Bluetooth Classic), and synthesizes intelligible speech locally on the receiving device via Text-to-Speech (TTS).

---

## 🚀 Key Features

- **100% Fully Offline:** Zero cloud dependencies, zero remote APIs, zero external telemetry.
- **Ultra-Low Bandwidth:** Transmits structured text packets rather than heavy raw audio (theoretical >90% bandwidth reduction).
- **10 Indian Languages Architecture:** Hindi, English, Marathi, Bengali, Tamil, Telugu, Gujarati, Kannada, Malayalam, and Odia.
- **Edge AI Speech Pipeline:** Powered by **Sherpa-ONNX** (built on ONNX Runtime Mobile C++ JNI) with INT8 quantized neural models (Silero VAD, Zipformer/Whisper-Tiny STT, VITS/Piper TTS).
- **Dual Operating Modes:**
  - **Push-to-Talk (PTT):** Walkie-talkie mode with tactile feedback and visual pulsing halo.
  - **Continuous Hands-Free:** Automated VAD pause segmentation (500ms silence threshold).
- **Emergency SOS Broadcast:** Preempts normal queue playback, requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, and triggers visual alert banners.
- **Zero-Internet D2D Transports:**
  - **Local Wi-Fi:** Direct TCP Sockets with Network Service Discovery (NSD) auto-peering.
  - **Bluetooth Classic:** Serial Port Profile (SPP / RFCOMM) socket communication.
  - **Embedded Bridge:** Byte-level UART/BLE specification for external mesh/LoRa hardware nodes.
- **Live Diagnostics & Benchmarking:**
  - $T_0 \to T_6$ real-time latency timestamp tracking.
  - Real-Time Factor (STT & TTS RTF) calculation.
  - In-app Levenshtein Word Error Rate (WER) ground-truth testbench.
  - Live PSS memory profiling and packet size comparison.

---

## 📐 Project Architecture

```
USER SPEAKS (Phone A)
       │
       ▼
 [Microphone] 16kHz 16-bit Mono PCM
       │
       ▼
 [Silero VAD] (ONNX INT8) - Detects Speech & 500ms Pauses
       │
       ▼
 [Sherpa-ONNX STT] (Zipformer / Whisper-Tiny INT8) - Local Transcription
       │
       ▼
 [Packet Framing] Magic Bytes + Seq + Timestamp + Priority + Lang + UTF-8 + CRC32
       │
       ▼
 [PeerTransport] Local Wi-Fi Direct TCP Socket / Bluetooth RFCOMM SPP
       │
═══════╡ LOW-BANDWIDTH D2D LINK (ZERO INTERNET) ╞══════════════════════════════
       │
       ▼
 [PeerTransport Receiver] (Phone B)
       │
       ▼
 [Packet Unframer & CRC32 Validator]
       │
       ▼
 [Priority & AudioFocus Orchestrator] (Emergency Preemption)
       │
       ▼
 [Sherpa-ONNX TTS] (VITS / Piper ONNX INT8) - Local Voice Synthesis
       │
       ▼
 [AudioTrack] 16kHz PCM Output to Speaker / Earpiece
       │
       ▼
 USER HEARS SPEECH (Phone B)
```

---

## 📱 Tech Stack & Build Configuration

- **Platform:** Android
- **Language:** Kotlin 1.9.22 (Coroutines + StateFlow)
- **UI:** Jetpack Compose + Material 3
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 34 (Android 14)
- **AGP:** 8.3.0
- **Gradle:** 8.4+
- **Edge AI Runtime:** Sherpa-ONNX (`com.k2fsa.sherpa.onnx:sherpa-onnx:1.10.19`)

---

## 🧪 Testing & Verification

Run automated unit tests for packet framing, VAD state transitions, and WER calculation:
```bash
./gradlew test
```

---

## 📄 Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md): Comprehensive system architecture & memory management.
- [NETWORK_PROTOCOL.md](NETWORK_PROTOCOL.md): Low-bandwidth binary packet framing specification.
- [MODEL_AUDIT.md](MODEL_AUDIT.md): 10-language model audit, licenses, and verification statuses.
- [DEMO_GUIDE.md](DEMO_GUIDE.md): Step-by-step physical two-phone offline demonstration protocol.
