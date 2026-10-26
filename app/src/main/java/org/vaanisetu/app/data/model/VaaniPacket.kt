package org.vaanisetu.app.data.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Message Priority levels for VaaniSetu protocol.
 * EMERGENCY > HIGH > NORMAL
 */
enum class MessagePriority(val value: Byte) {
    NORMAL(0x00),
    HIGH(0x01),
    EMERGENCY(0x02);

    companion object {
        fun fromByte(b: Byte): MessagePriority = entries.find { it.value == b } ?: NORMAL
    }
}

/**
 * Low-Bandwidth Binary Framed Packet for VaaniSetu (Protocol Version 2).
 * 
 * Supports genuine offline GPS/GNSS sender source location metadata.
 * Backward compatible with Protocol Version 1.
 * 
 * Total Packet Size: ~31 to ~67 + N bytes (where N = UTF-8 text length in bytes)
 * Yields ~98%+ data reduction compared to transmitting 16kHz raw PCM audio.
 */
data class VaaniPacket(
    val protocolVersion: Byte = CURRENT_VERSION,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val sequenceNumber: Long = 0L,
    val timestampT3: Long = System.currentTimeMillis(),
    val senderIdHash: Int = 0,
    val languageCode: String = "hi", // 2-char ISO code e.g. "hi", "en", "ta"
    val textPayload: String = "",
    val location: SourceLocationInfo? = null
) {
    companion object {
        const val MAGIC_BYTE_1: Byte = 0x56 // 'V'
        const val MAGIC_BYTE_2: Byte = 0x53 // 'S'
        const val VERSION_1: Byte = 0x01
        const val VERSION_2: Byte = 0x02
        const val CURRENT_VERSION: Byte = VERSION_2

        const val PRE_PAYLOAD_HEADER_SIZE = 24 // Fixed pre-payload header size (bytes 0..23)
        const val CRC_SIZE = 4 // CRC32 trailing checksum (4 bytes)
        const val HEADER_SIZE = 28 // Minimum framing overhead (24 header + 4 CRC)

        /**
         * Serializes a VaaniPacket to framed binary byte array with CRC32 integrity check.
         */
        fun serialize(packet: VaaniPacket): ByteArray {
            val textBytes = packet.textPayload.toByteArray(StandardCharsets.UTF_8)
            val langBytes = packet.languageCode.padEnd(2, ' ').take(2).toByteArray(StandardCharsets.US_ASCII)

            // Build payload byte array first to determine total payload length
            val payloadBaos = ByteArrayOutputStream()
            val payloadDos = DataOutputStream(payloadBaos)

            if (packet.protocolVersion == VERSION_1) {
                // Version 1 format: Raw UTF-8 text only
                payloadDos.write(textBytes)
            } else {
                // Version 2+ format: [textLength: Short] [textBytes] [hasLocation: Byte] [location extension if true]
                payloadDos.writeShort(textBytes.size and 0xFFFF)
                payloadDos.write(textBytes)

                val hasLoc = packet.location != null && packet.location.hasLocation
                payloadDos.writeByte(if (hasLoc) 0x01 else 0x00)

                if (hasLoc && packet.location != null) {
                    val loc = packet.location
                    payloadDos.writeDouble(loc.latitude)
                    payloadDos.writeDouble(loc.longitude)
                    payloadDos.writeFloat(loc.accuracyMeters)
                    payloadDos.writeLong(loc.locationTimestamp)
                    payloadDos.writeByte(loc.locationFreshness.value.toInt())
                    payloadDos.writeDouble(loc.altitudeMeters)

                    val srcBytes = loc.locationSource.toByteArray(StandardCharsets.US_ASCII)
                    val srcLen = srcBytes.size.coerceAtMost(255)
                    payloadDos.writeByte(srcLen)
                    payloadDos.write(srcBytes, 0, srcLen)

                    val addrBytes = loc.address?.toByteArray(StandardCharsets.UTF_8)
                    if (addrBytes != null && addrBytes.isNotEmpty()) {
                        val addrLen = addrBytes.size.coerceAtMost(65535)
                        payloadDos.writeShort(addrLen)
                        payloadDos.write(addrBytes, 0, addrLen)
                    } else {
                        payloadDos.writeShort(0)
                    }
                }
            }
            payloadDos.flush()
            val payloadBytes = payloadBaos.toByteArray()

            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            // 1. Magic Bytes (2 bytes)
            dos.writeByte(MAGIC_BYTE_1.toInt())
            dos.writeByte(MAGIC_BYTE_2.toInt())

            // 2. Protocol Version (1 byte)
            dos.writeByte(packet.protocolVersion.toInt())

            // 3. Priority (1 byte)
            dos.writeByte(packet.priority.value.toInt())

            // 4. Sequence Number (4 bytes uint32)
            dos.writeInt((packet.sequenceNumber and 0xFFFFFFFFL).toInt())

            // 5. Timestamp T3 (8 bytes int64 ms)
            dos.writeLong(packet.timestampT3)

            // 6. Sender ID Hash (4 bytes int32)
            dos.writeInt(packet.senderIdHash)

            // 7. Language Code (2 bytes ASCII)
            dos.write(langBytes)

            // 8. Total Payload Length (2 bytes uint16)
            dos.writeShort(payloadBytes.size and 0xFFFF)

            // 9. Payload bytes (Text + Optional Location extension)
            dos.write(payloadBytes)

            dos.flush()
            val packetWithoutCrc = baos.toByteArray()

            // 10. Compute CRC32
            val crc = CRC32()
            crc.update(packetWithoutCrc)
            val crcValue = crc.value

            val finalBaos = ByteArrayOutputStream()
            val finalDos = DataOutputStream(finalBaos)
            finalDos.write(packetWithoutCrc)
            finalDos.writeInt((crcValue and 0xFFFFFFFFL).toInt())
            finalDos.flush()

            return finalBaos.toByteArray()
        }

        /**
         * Deserializes a binary byte array into a validated VaaniPacket.
         * Throws IllegalArgumentException if magic bytes or CRC32 checksum fail.
         */
        fun deserialize(bytes: ByteArray): VaaniPacket {
            if (bytes.size < HEADER_SIZE) {
                throw IllegalArgumentException("Packet too short: ${bytes.size} bytes (min $HEADER_SIZE)")
            }

            val bais = ByteArrayInputStream(bytes)
            val dis = DataInputStream(bais)

            // Validate Magic Bytes
            val m1 = dis.readByte()
            val m2 = dis.readByte()
            if (m1 != MAGIC_BYTE_1 || m2 != MAGIC_BYTE_2) {
                throw IllegalArgumentException("Invalid magic bytes: $m1, $m2 (expected VS)")
            }

            val version = dis.readByte()
            val priority = MessagePriority.fromByte(dis.readByte())
            val seq = dis.readInt().toLong() and 0xFFFFFFFFL
            val timestampT3 = dis.readLong()
            val senderHash = dis.readInt()

            val langBytes = ByteArray(2)
            dis.readFully(langBytes)
            val langCode = String(langBytes, StandardCharsets.US_ASCII).trim()

            val totalPayloadLength = dis.readUnsignedShort()
            if (bytes.size < PRE_PAYLOAD_HEADER_SIZE + totalPayloadLength + CRC_SIZE) {
                throw IllegalArgumentException("Incomplete packet payload: expected $totalPayloadLength bytes")
            }

            // Verify CRC32 Checksum
            val crc = CRC32()
            crc.update(bytes, 0, bytes.size - CRC_SIZE)
            val calculatedCrc = crc.value

            val packetCrcDis = DataInputStream(ByteArrayInputStream(bytes, bytes.size - CRC_SIZE, CRC_SIZE))
            val packetCrc = packetCrcDis.readInt().toLong() and 0xFFFFFFFFL

            if (packetCrc != calculatedCrc) {
                throw IllegalArgumentException("CRC32 mismatch: packet=$packetCrc, calc=$calculatedCrc")
            }

            val text: String
            val locationInfo: SourceLocationInfo?

            if (version == VERSION_1) {
                val textBytes = ByteArray(totalPayloadLength)
                dis.readFully(textBytes)
                text = String(textBytes, StandardCharsets.UTF_8)
                locationInfo = null
            } else {
                val textLength = dis.readUnsignedShort()
                val textBytes = ByteArray(textLength)
                dis.readFully(textBytes)
                text = String(textBytes, StandardCharsets.UTF_8)

                val hasLocByte = dis.readByte()
                if (hasLocByte.toInt() == 0x01) {
                    val lat = dis.readDouble()
                    val lon = dis.readDouble()
                    val acc = dis.readFloat()
                    val locTime = dis.readLong()
                    val freshnessByte = dis.readByte()
                    val alt = dis.readDouble()

                    val srcLen = dis.readUnsignedByte()
                    val srcBytes = ByteArray(srcLen)
                    dis.readFully(srcBytes)
                    val src = String(srcBytes, StandardCharsets.US_ASCII)

                    val addrLen = dis.readUnsignedShort()
                    val addr = if (addrLen > 0) {
                        val addrBytes = ByteArray(addrLen)
                        dis.readFully(addrBytes)
                        String(addrBytes, StandardCharsets.UTF_8)
                    } else null

                    locationInfo = SourceLocationInfo(
                        hasLocation = true,
                        latitude = lat,
                        longitude = lon,
                        accuracyMeters = acc,
                        locationTimestamp = locTime,
                        locationFreshness = LocationFreshness.fromByte(freshnessByte),
                        locationSource = src,
                        altitudeMeters = alt,
                        address = addr
                    )
                } else {
                    locationInfo = null
                }
            }

            return VaaniPacket(
                protocolVersion = version,
                priority = priority,
                sequenceNumber = seq,
                timestampT3 = timestampT3,
                senderIdHash = senderHash,
                languageCode = langCode,
                textPayload = text,
                location = locationInfo
            )
        }
    }
}
