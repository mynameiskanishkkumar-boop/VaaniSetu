# VaaniSetu — Two-Phone Offline Demonstration Protocol
**Smart India Hackathon 2026 | Evaluator Walkthrough Guide**

---

## Prerequisites
- Two Android physical devices (Phone A and Phone B) with VaaniSetu installed.
- **Airplane Mode enabled on both devices** (Zero cellular data, Zero Internet connection).

---

## Step-by-Step Demonstration Procedure

### Step 1: Establish Local D2D Link
1. On **Phone A**, enable **Portable Wi-Fi Hotspot** (Settings $\to$ Hotspot).
2. On **Phone B**, connect to Phone A's Wi-Fi network.
3. Open **VaaniSetu** on both phones.
4. Tap **Connection** (Status chip at the top).
5. On **Phone A**, tap **"Start Server (Listen)"**.
6. On **Phone B**, enter Phone A's IP address (default `192.168.43.1` or tap auto-discovered peer) and tap **"Connect to Peer"**.
7. Observe that the status chip turns **Green (Connected)** on both phones.

### Step 2: Language Selection (Same Language on Both Devices)
1. Tap the **Language** chip on both Phone A and Phone B.
2. Select **Hindi (हिन्दी)** on both devices.
3. Confirm that the status badge indicates `STT: VERIFIED` and `TTS: VERIFIED`.

### Step 3: Push-to-Talk (PTT) Voice Transceiver Demonstration
1. On **Phone A**, press and hold the large central **"HOLD TO TALK"** button.
2. Speak clearly into the microphone:
   > *"वाणीसेतु आपदा प्रबंधन दल को तुरंत सहायता की आवश्यकता है।"*
3. Release the PTT button.
4. **Observe Phone A:**
   - Speech is segmented locally.
   - Sherpa-ONNX STT transcribes the Hindi sentence locally.
   - Text is packaged into a compact binary packet (< 100 bytes) and transmitted over local Wi-Fi.
5. **Observe Phone B:**
   - Receives the binary packet.
   - Validates CRC32 checksum.
   - Synthesizes the Hindi speech locally via Sherpa-ONNX VITS.
   - Plays the audio aloud through Phone B's speaker.

### Step 4: Emergency SOS Preemption Test
1. On **Phone A**, tap the red **"BROADCAST EMERGENCY SOS"** button.
2. **Observe Phone B:**
   - Emergency banner flashes red on Phone B.
   - Standard audio focus is preempted via `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`.
   - Emergency audio is announced at maximum permissible volume.

### Step 5: Live Diagnostics & Telemetry Review
1. On **Phone B**, tap the top-right **Diagnostics** icon.
2. View the measured metrics:
   - **Bandwidth Efficiency:** Raw 16kHz PCM bytes vs. Transmitted packet bytes (demonstrating massive bandwidth reduction).
   - **Latency Timeline:** $T_0 \to T_6$ timestamps, STT latency, Network latency, and TTS latency.
   - **Real-Time Factor (RTF):** STT RTF and TTS RTF.
   - **Resource Profile:** Real-time RAM PSS memory footprint.
   - **Accuracy Evaluator:** Tap "Run Offline WER Benchmark" to execute the in-app Levenshtein distance test.
