package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

object Id3TagEditor {
    private const val TAG = "Id3TagEditor"

    data class Id3Data(
        val lyrics: String? = null,
        val coverBytes: ByteArray? = null,
        val mimeType: String? = null,
        val otherFrames: List<Id3Frame> = emptyList()
    )

    data class Id3Frame(
        val id: String,
        val flags: ByteArray,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Id3Frame) return false
            if (id != other.id) return false
            if (!flags.contentEquals(other.flags)) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            result = result
            return result
        }
    }

    /**
     * Reads ID3v2.3 / ID3v2.4 tags from a media file URI.
     */
    fun readTag(context: Context, uri: Uri): Id3Data {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return Id3Data()
            
            val header = ByteArray(10)
            if (inputStream.read(header) != 10) return Id3Data()

            if (header[0] != 'I'.toByte() || header[1] != 'D'.toByte() || header[2] != '3'.toByte()) {
                Log.d(TAG, "No ID3v2 header found")
                return Id3Data()
            }

            val majorVersion = header[3].toInt() and 0xFF
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                       ((header[7].toInt() and 0x7F) shl 14) or
                       ((header[8].toInt() and 0x7F) shl 7) or
                       (header[9].toInt() and 0x7F)

            if (size <= 0 || size > 10 * 1024 * 1024) { // Max 10MB limit for safety
                return Id3Data()
            }

            val tagBytes = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val read = inputStream.read(tagBytes, totalRead, size - totalRead)
                if (read == -1) break
                totalRead += read
            }

            return parseFrames(tagBytes, majorVersion)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ID3 tags: ${e.message}", e)
            return Id3Data()
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
        }
    }

    private fun parseFrames(tagBytes: ByteArray, majorVersion: Int): Id3Data {
        var offset = 0
        var lyrics: String? = null
        var coverBytes: ByteArray? = null
        var mimeType: String? = null
        val otherFrames = mutableListOf<Id3Frame>()

        while (offset + 10 <= tagBytes.size) {
            val frameIdBytes = ByteArray(4)
            System.arraycopy(tagBytes, offset, frameIdBytes, 0, 4)
            val frameId = String(frameIdBytes, Charsets.US_ASCII)

            // Padding / terminator block
            if (frameId.isBlank() || frameId[0] == '\u0000') {
                break
            }

            val b0 = tagBytes[offset + 4]
            val b1 = tagBytes[offset + 5]
            val b2 = tagBytes[offset + 6]
            val b3 = tagBytes[offset + 7]

            // In ID3v2.3, size is standard 32-bit big-endian int. In ID3v2.4 it's synchsafe.
            val frameSize = if (majorVersion == 4) {
                ((b0.toInt() and 0x7F) shl 21) or
                ((b1.toInt() and 0x7F) shl 14) or
                ((b2.toInt() and 0x7F) shl 7) or
                (b3.toInt() and 0x7F)
            } else {
                ((b0.toInt() and 0xFF) shl 24) or
                ((b1.toInt() and 0xFF) shl 16) or
                ((b2.toInt() and 0xFF) shl 8) or
                (b3.toInt() and 0xFF)
            }

            val flags = ByteArray(2)
            flags[0] = tagBytes[offset + 8]
            flags[1] = tagBytes[offset + 9]

            offset += 10

            if (offset + frameSize > tagBytes.size || frameSize < 0) {
                break
            }

            val payload = ByteArray(frameSize)
            System.arraycopy(tagBytes, offset, payload, 0, frameSize)
            offset += frameSize

            when (frameId) {
                "USLT" -> {
                    try {
                        lyrics = parseUsltFrame(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing USLT frame", e)
                    }
                }
                "APIC" -> {
                    try {
                        val apic = parseApicFrame(payload)
                        if (apic != null) {
                            coverBytes = apic.first
                            mimeType = apic.second
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing APIC frame", e)
                    }
                }
                else -> {
                    otherFrames.add(Id3Frame(frameId, flags, payload))
                }
            }
        }

        return Id3Data(lyrics, coverBytes, mimeType, otherFrames)
    }

    private fun parseUsltFrame(payload: ByteArray): String? {
        if (payload.size <= 5) return null
        val encodingInt = payload[0].toInt() and 0xFF
        // language (3 bytes) is payload[1], [2], [3]
        // find content descriptor descriptor terminator
        val encoding = getEncoding(encodingInt)
        var descOffset = 4
        if (encodingInt == 1 || encodingInt == 2) {
            // UTF-16, terminator is twin 0x00 bytes
            while (descOffset + 1 < payload.size) {
                if (payload[descOffset] == 0.toByte() && payload[descOffset + 1] == 0.toByte()) {
                    descOffset += 2
                    break
                }
                descOffset += 2
            }
        } else {
            // ISO-8859-1 or UTF-8, terminator is single 0x00
            while (descOffset < payload.size) {
                if (payload[descOffset] == 0.toByte()) {
                    descOffset += 1
                    break
                }
                descOffset++
            }
        }

        if (descOffset >= payload.size) return null
        val lyricsLen = payload.size - descOffset
        return String(payload, descOffset, lyricsLen, encoding).trim()
    }

    private fun parseApicFrame(payload: ByteArray): Pair<ByteArray, String>? {
        if (payload.size <= 5) return null
        val encodingInt = payload[0].toInt() and 0xFF
        
        // Find MIME type string
        var mimeOffset = 1
        while (mimeOffset < payload.size) {
            if (payload[mimeOffset] == 0.toByte()) {
                break
            }
            mimeOffset++
        }
        if (mimeOffset >= payload.size) return null
        val mimeType = String(payload, 1, mimeOffset - 1, Charsets.US_ASCII)
        
        val pictureTypeOffset = mimeOffset + 1
        if (pictureTypeOffset >= payload.size) return null
        // val pictureType = payload[pictureTypeOffset].toInt() // 3 is cover
        
        var descOffset = pictureTypeOffset + 1
        val encoding = getEncoding(encodingInt)
        if (encodingInt == 1 || encodingInt == 2) {
            while (descOffset + 1 < payload.size) {
                if (payload[descOffset] == 0.toByte() && payload[descOffset + 1] == 0.toByte()) {
                    descOffset += 2
                    break
                }
                descOffset += 2
            }
        } else {
            while (descOffset < payload.size) {
                if (payload[descOffset] == 0.toByte()) {
                    descOffset += 1
                    break
                }
                descOffset++
            }
        }
        
        if (descOffset >= payload.size) return null
        val imageLen = payload.size - descOffset
        val imageBytes = ByteArray(imageLen)
        System.arraycopy(payload, descOffset, imageBytes, 0, imageLen)
        return Pair(imageBytes, mimeType)
    }

    private fun getEncoding(enc: Int): java.nio.charset.Charset {
        return when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
    }

    /**
     * Saves updated lyrics and/or cover bytes to the given MP3 file URI.
     */
    fun saveTag(
        context: Context,
        uri: Uri,
        newLyrics: String?,
        newCoverBytes: ByteArray?,
        newMimeType: String?
    ): Boolean {
        var inputStream: InputStream? = null
        val audioBytesStream = ByteArrayOutputStream()

        try {
            // 1. Read existing ID3 and split audio data
            inputStream = context.contentResolver.openInputStream(uri) ?: return false
            
            val header = ByteArray(10)
            val readHeaderCount = inputStream.read(header)
            
            var existingTagSize = 0
            var hasId3 = false
            
            if (readHeaderCount == 10 && header[0] == 'I'.toByte() && header[1] == 'D'.toByte() && header[2] == '3'.toByte()) {
                hasId3 = true
                existingTagSize = ((header[6].toInt() and 0x7F) shl 21) or
                                  ((header[7].toInt() and 0x7F) shl 14) or
                                  ((header[8].toInt() and 0x7F) shl 7) or
                                  (header[9].toInt() and 0x7F)
            }

            val existingData = if (hasId3 && existingTagSize > 0) {
                // Read existing tag frames
                val tagBytes = ByteArray(existingTagSize)
                var totalRead = 0
                while (totalRead < existingTagSize) {
                    val read = inputStream.read(tagBytes, totalRead, existingTagSize - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                
                val majorVersion = header[3].toInt() and 0xFF
                parseFrames(tagBytes, majorVersion)
            } else {
                Id3Data()
            }

            // Read the rest of file (audio stream data)
            if (!hasId3 && readHeaderCount > 0) {
                audioBytesStream.write(header, 0, readHeaderCount)
            }
            
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                audioBytesStream.write(buffer, 0, read)
            }
            inputStream.close()
            inputStream = null

            // 2. Generate updated frames
            val framesToWrite = mutableListOf<Id3Frame>()
            
            // Add other existing frames that are not USLT or APIC
            framesToWrite.addAll(existingData.otherFrames)

            // Write updated lyrics as USLT
            if (!newLyrics.isNullOrBlank()) {
                val lyrBytes = newLyrics.toByteArray(Charsets.UTF_8)
                val payload = ByteArray(1 + 3 + 1 + lyrBytes.size)
                payload[0] = 0x03 // UTF-8
                payload[1] = 'e'.toByte()
                payload[2] = 'n'.toByte()
                payload[3] = 'g'.toByte()
                payload[4] = 0x00 // No description, immediate terminator
                System.arraycopy(lyrBytes, 0, payload, 5, lyrBytes.size)
                framesToWrite.add(Id3Frame("USLT", ByteArray(2), payload))
            } else if (existingData.lyrics != null && newLyrics != null) {
                // If user cleared lyrics explicitly
                // omit adding USLT frame
            } else if (existingData.lyrics != null) {
                // Keep keeping lyrics if not explicitly set other way
                val lyrBytes = existingData.lyrics.toByteArray(Charsets.UTF_8)
                val payload = ByteArray(1 + 3 + 1 + lyrBytes.size)
                payload[0] = 0x03
                payload[1] = 'e'.toByte()
                payload[2] = 'n'.toByte()
                payload[3] = 'g'.toByte()
                payload[4] = 0x00
                System.arraycopy(lyrBytes, 0, payload, 5, lyrBytes.size)
                framesToWrite.add(Id3Frame("USLT", ByteArray(2), payload))
            }

            // Write updated cover art as APIC
            if (newCoverBytes != null && newCoverBytes.isNotEmpty()) {
                val mime = newMimeType ?: "image/jpeg"
                val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
                val payload = ByteArray(1 + mimeBytes.size + 1 + 1 + 1 + newCoverBytes.size)
                payload[0] = 0x00 // ISO-8859-1 for mime/description
                System.arraycopy(mimeBytes, 0, payload, 1, mimeBytes.size)
                val terminatorIdx = 1 + mimeBytes.size
                payload[terminatorIdx] = 0x00
                payload[terminatorIdx + 1] = 0x03 // Front cover
                payload[terminatorIdx + 2] = 0x00 // Empty description terminator
                System.arraycopy(newCoverBytes, 0, payload, terminatorIdx + 3, newCoverBytes.size)
                framesToWrite.add(Id3Frame("APIC", ByteArray(2), payload))
            } else if (existingData.coverBytes != null && newCoverBytes == null) {
                // Keep original cover art if not explicitly overridden
                val mime = existingData.mimeType ?: "image/jpeg"
                val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
                val payload = ByteArray(1 + mimeBytes.size + 1 + 1 + 1 + existingData.coverBytes.size)
                payload[0] = 0x00
                System.arraycopy(mimeBytes, 0, payload, 1, mimeBytes.size)
                val terminatorIdx = 1 + mimeBytes.size
                payload[terminatorIdx] = 0x00
                payload[terminatorIdx + 1] = 0x03
                payload[terminatorIdx + 2] = 0x00
                System.arraycopy(existingData.coverBytes, 0, payload, terminatorIdx + 3, existingData.coverBytes.size)
                framesToWrite.add(Id3Frame("APIC", ByteArray(2), payload))
            }

            // 3. Assemble ID3 Tag Bytes
            val newTagPayload = ByteArrayOutputStream()
            for (frame in framesToWrite) {
                newTagPayload.write(frame.id.toByteArray(Charsets.US_ASCII))
                
                // 4-byte size (normal 32-bit big endian for ID3v2.3)
                val s = frame.payload.size
                newTagPayload.write((s ushr 24) and 0xFF)
                newTagPayload.write((s ushr 16) and 0xFF)
                newTagPayload.write((s ushr 8) and 0xFF)
                newTagPayload.write(s and 0xFF)

                // 2-byte flags
                newTagPayload.write(frame.flags)

                // Payload
                newTagPayload.write(frame.payload)
            }

            // Add some padding bytes (e.g. 1024 bytes) so tag header can accommodate expansions
            val paddingSize = 1024
            newTagPayload.write(ByteArray(paddingSize))

            val totalTagBytes = newTagPayload.toByteArray()
            val synchsafeTotalSize = intToSynchsafe(totalTagBytes.size)

            // Compose output
            var outputStream: OutputStream? = null
            try {
                outputStream = context.contentResolver.openOutputStream(uri, "rwt") ?: return false
                
                // Write main tag header
                outputStream.write('I'.toInt())
                outputStream.write('D'.toInt())
                outputStream.write('3'.toInt())
                outputStream.write(0x03) // version 2.3
                outputStream.write(0x00) // subversion
                outputStream.write(0x00) // flags
                outputStream.write(synchsafeTotalSize)

                // Write tag frames + padding
                outputStream.write(totalTagBytes)

                // Write original audio data
                outputStream.write(audioBytesStream.toByteArray())
                outputStream.flush()
                Log.d(TAG, "Successfully updated and saved MP3 file tags!")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing updated file bytes: ${e.message}", e)
                return false
            } finally {
                try { outputStream?.close() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving ID3 tags: ${e.message}", e)
            return false
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
        }
    }

    private fun intToSynchsafe(value: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = ((value ushr 21) and 0x7F).toByte()
        b[1] = ((value ushr 14) and 0x7F).toByte()
        b[2] = ((value ushr 7) and 0x7F).toByte()
        b[3] = (value and 0x7F).toByte()
        return b
    }
}
