# VaaniSetu Binary Network Protocol Specification
**Document Version:** 1.0.0  
**Transport Support:** TCP Sockets, Bluetooth RFCOMM (SPP), UART/BLE Serial Bridge

---

## 1. Packet Structure & Offsets

All multi-byte numeric fields are encoded in **Big-Endian** (Network Byte Order).

```
Offset   Size (bytes)   Type     Field Name         Description
─────────────────────────────────────────────────────────────────────────────────
0        2              Bytes    Magic Bytes        0x56 0x53 (ASCII 'V', 'S')
2        1              uint8    Protocol Version   0x01
3        1              uint8    Priority           0x00=NORMAL, 0x01=HIGH, 0x02=EMERGENCY
4        4              uint32   Sequence Number    Incremental counter per sender
8        8              int64    Timestamp (T3)     Unix epoch milliseconds when packet was sent
16       4              int32    Sender ID Hash     Truncated device UUID hash
20       2              ASCII    Language Code      2-character ISO 639-1 code (e.g. "hi", "en", "ta")
22       2              uint16   Payload Length (N) Length of text string in bytes (N <= 1024)
24       N              UTF-8    Text Payload       UTF-8 encoded transcript text
24+N     4              uint32   CRC32 Checksum     CRC32 over bytes [0 .. 23+N]
─────────────────────────────────────────────────────────────────────────────────
Total Packet Length = 28 + N bytes
```

---

## 2. Priority Hierarchy

- `0x02 (EMERGENCY)`: Highest priority. Preempts current playback, triggers immediate `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, and max permissible stream volume.
- `0x01 (HIGH)`: High priority alert. Queued ahead of normal messages.
- `0x00 (NORMAL)`: Standard conversational message.

---

## 3. Integrity & Error Handling

1. **Magic Bytes Validation:** If bytes 0-1 are not `0x56 0x53`, the frame is discarded.
2. **Length Prefix:** Receiver reads the fixed 28-byte header first to extract `Payload Length (N)`, then reads $N + 4$ bytes.
3. **CRC32 Checksum:** Computed over the entire packet header + payload. Mismatch results in instant packet rejection and socket state recovery.
