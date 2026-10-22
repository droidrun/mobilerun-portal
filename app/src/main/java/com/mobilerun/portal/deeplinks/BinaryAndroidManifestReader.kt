package com.mobilerun.portal.deeplinks

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque

/** A small, bounded reader for the public Android binary XML format used in installed APKs. */
object BinaryAndroidManifestReader {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_NODES = 10_000
    private const val MAX_DEPTH = 128
    private const val NO_INDEX = -1

    /** Throws [IllegalArgumentException] for unsupported, malformed, or excessively large XML. */
    fun read(bytes: ByteArray): ManifestElement = Parser(bytes).read()

    private class Parser(private val bytes: ByteArray) {
        private lateinit var pool: StringPool
        private var seenPool = false
        private var nodeCount = 0

        fun read(): ManifestElement {
            require(bytes.size in 8..MAX_BYTES) { "Binary manifest size must be between 8 and $MAX_BYTES bytes" }
            val document = chunk(0, bytes.size)
            require(document.type == 0x0003 && document.end == bytes.size) { "Expected a complete binary XML document" }
            val stack = ArrayDeque<OpenElement>()
            var root: ManifestElement? = null
            var position = document.headerEnd
            while (position < document.end) {
                val current = chunk(position, document.end)
                when (current.type) {
                    0x0001 -> {
                        require(!seenPool && nodeCount == 0) { "Unexpected binary XML string pool" }
                        pool = StringPool(current)
                        seenPool = true
                    }
                    0x0180 -> {
                        require(seenPool && nodeCount == 0 && (current.end - current.headerEnd) % 4 == 0) {
                            "Invalid binary XML resource map"
                        }
                    }
                    0x0100, 0x0101 -> {
                        requirePool()
                        requireNodeHeader(current, 8)
                        pool.optional(i32(current.headerEnd))
                        pool.required(i32(current.headerEnd + 4))
                    }
                    0x0102 -> {
                        requirePool()
                        require(root == null) { "Multiple binary XML root elements" }
                        require(++nodeCount <= MAX_NODES && stack.size < MAX_DEPTH) { "Binary manifest nesting or node limit exceeded" }
                        requireNodeHeader(current, 20)
                        val extension = current.headerEnd
                        val namespace = pool.optional(i32(extension))
                        val name = pool.required(i32(extension + 4))
                        val attributeStart = u16(extension + 8)
                        val attributeSize = u16(extension + 10)
                        val count = u16(extension + 12)
                        require(attributeStart >= 20 && attributeSize >= 20) { "Invalid binary XML attribute layout" }
                        val start = extension.toLong() + attributeStart
                        val end = start + count.toLong() * attributeSize
                        require(start <= current.end && end <= current.end) { "Truncated binary XML attributes" }
                        val attributes = ArrayList<ManifestAttribute>(count)
                        repeat(count) { index ->
                            val offset = (start + index.toLong() * attributeSize).toInt()
                            val attributeNamespace = pool.optional(i32(offset))
                            val attributeName = pool.required(i32(offset + 4))
                            val raw = pool.optional(i32(offset + 8))
                            require(u16(offset + 12) >= 8 && u16(offset + 12) <= attributeSize - 12) {
                                "Invalid binary XML typed value"
                            }
                            val type = u8(offset + 15)
                            val data = i32(offset + 16)
                            val resourceId = if (type == 0x01 || type == 0x07) data else null
                            attributes += ManifestAttribute(
                                attributeNamespace,
                                attributeName,
                                // Resource references must stay recognizable even when AAPT retained the raw text.
                                if (resourceId != null) "@0x${hex(data)}" else raw ?: typedValue(type, data),
                                resourceId,
                            )
                        }
                        stack.addLast(OpenElement(namespace, name, attributes))
                    }
                    0x0103 -> {
                        requirePool()
                        requireNodeHeader(current, 8)
                        require(stack.isNotEmpty()) { "Unexpected binary XML closing element" }
                        val namespace = pool.optional(i32(current.headerEnd))
                        val name = pool.required(i32(current.headerEnd + 4))
                        val open = stack.removeLast()
                        require(open.name == name && open.namespace == namespace) { "Mismatched binary XML closing element" }
                        val element = ManifestElement(open.name, open.attributes, open.children.toList())
                        if (stack.isEmpty()) root = element else stack.last.children += element
                    }
                    0x0104 -> {
                        requirePool()
                        requireNodeHeader(current, 12)
                        require(stack.isNotEmpty()) { "Binary XML text outside the root element" }
                        pool.required(i32(current.headerEnd))
                    }
                    else -> throw IllegalArgumentException("Unsupported binary XML chunk: 0x${current.type.toString(16)}")
                }
                position = current.end
            }
            require(stack.isEmpty()) { "Unclosed binary XML element" }
            return requireNotNull(root) { "Binary XML document has no root element" }
        }

        private fun typedValue(type: Int, data: Int): String = when (type) {
            0x00 -> if (data == 1) "@empty" else "@null"
            0x02, 0x08 -> "?0x${hex(data)}"
            0x03 -> pool.required(data)
            0x04 -> Float.fromBits(data).toString()
            0x05 -> complexValue(data, listOf("px", "dip", "sp", "pt", "in", "mm"))
            0x06 -> complexValue(data, listOf("%", "%p"), 100f)
            0x10 -> data.toString()
            0x11 -> "0x${hex(data)}"
            0x12 -> (data != 0).toString()
            in 0x1c..0x1f -> "#${hex(data)}"
            else -> throw IllegalArgumentException("Unsupported binary XML attribute type: 0x${type.toString(16)}")
        }

        private fun complexValue(data: Int, units: List<String>, multiplier: Float = 1f): String {
            val unit = data and 0xf
            require(unit in units.indices) { "Invalid binary XML complex value unit" }
            val radix = listOf(1f / 256, 1f / 32768, 1f / 8388608, 1f / 2147483648f)[(data shr 4) and 3]
            return ((data and -256) * radix * multiplier).toString() + units[unit]
        }

        private fun requirePool() = require(seenPool) { "Binary XML string pool is missing" }

        private fun requireNodeHeader(chunk: Chunk, extensionBytes: Int) {
            require(chunk.headerEnd - chunk.start >= 16 && chunk.end - chunk.headerEnd >= extensionBytes) {
                "Truncated binary XML node"
            }
        }

        private inner class StringPool(chunk: Chunk) {
            private val offsetsStart: Int
            private val count: Int
            private val dataStart: Int
            private val dataEnd: Int
            private val utf8: Boolean
            private val cache = HashMap<Int, String>()
            private var decodedCharacters = 0L

            init {
                require(chunk.headerEnd - chunk.start >= 28) { "Truncated binary XML string pool" }
                count = i32(chunk.start + 8)
                val styleCount = i32(chunk.start + 12)
                utf8 = i32(chunk.start + 16) and 0x100 != 0
                val stringsStart = i32(chunk.start + 20)
                val stylesStart = i32(chunk.start + 24)
                offsetsStart = chunk.headerEnd
                val offsetsEnd = offsetsStart.toLong() + (count.toLong() + styleCount) * 4
                val stringsAbsolute = chunk.start.toLong() + stringsStart
                val stylesAbsolute = if (stylesStart == 0) chunk.end.toLong() else chunk.start.toLong() + stylesStart
                require(count >= 0 && styleCount >= 0 && offsetsEnd <= stringsAbsolute &&
                    stringsAbsolute <= stylesAbsolute && stylesAbsolute <= chunk.end &&
                    (styleCount == 0 || stylesStart != 0)) { "Invalid binary XML string pool bounds" }
                dataStart = stringsAbsolute.toInt()
                dataEnd = stylesAbsolute.toInt()
            }

            fun optional(index: Int): String? = if (index == NO_INDEX) null else required(index)

            fun required(index: Int): String {
                require(index >= 0 && index < count) { "Invalid binary XML string index" }
                val relative = i32(offsetsStart + index * 4)
                val absolute = dataStart.toLong() + relative
                require(relative >= 0 && absolute < dataEnd) { "Invalid binary XML string offset" }
                return cache.getOrPut(absolute.toInt()) {
                    val cursor = StringCursor(absolute.toInt(), dataEnd)
                    val length = if (utf8) cursor.length8() else cursor.length16()
                    val encodedLength = if (utf8) cursor.length8().toLong() else length.toLong() * 2
                    val terminatorSize = if (utf8) 1 else 2
                    require(cursor.position.toLong() + encodedLength + terminatorSize <= dataEnd) {
                        "Truncated binary XML string"
                    }
                    val end = cursor.position + encodedLength.toInt()
                    require(u8(end) == 0 && (utf8 || u8(end + 1) == 0)) { "Unterminated binary XML string" }
                    decodedCharacters += length
                    require(decodedCharacters <= MAX_BYTES) { "Binary XML decoded string limit exceeded" }
                    val decoder = Charsets.UTF_16LE.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val decoded = try {
                        if (utf8) decodeUtf8(cursor.position, end, length)
                        else decoder.decode(ByteBuffer.wrap(bytes, cursor.position, encodedLength.toInt())).toString()
                    } catch (error: java.nio.charset.CharacterCodingException) {
                        throw IllegalArgumentException("Invalid binary XML string encoding", error)
                    }
                    require(decoded.length == length) { "Invalid binary XML string length" }
                    decoded
                }
            }
        }

        // AAPT2 writes supplementary characters as two three-byte surrogate sequences (CESU-8).
        // Accept those and ordinary four-byte UTF-8 while rejecting malformed/overlong sequences.
        private fun decodeUtf8(start: Int, end: Int, expectedLength: Int): String {
            val characters = CharArray(expectedLength)
            var output = 0
            var position = start
            fun continuation(): Int {
                require(position < end) { "Truncated binary XML UTF-8 sequence" }
                val value = u8(position++)
                require(value and 0xc0 == 0x80) { "Invalid binary XML UTF-8 continuation" }
                return value and 0x3f
            }
            fun append(value: Int) {
                require(output < characters.size) { "Invalid binary XML UTF-8 string length" }
                characters[output++] = value.toChar()
            }
            while (position < end) {
                val first = u8(position++)
                when {
                    first < 0x80 -> append(first)
                    first and 0xe0 == 0xc0 -> {
                        val value = ((first and 0x1f) shl 6) or continuation()
                        require(value >= 0x80 || value == 0) { "Overlong binary XML UTF-8 sequence" }
                        append(value)
                    }
                    first and 0xf0 == 0xe0 -> {
                        val value = ((first and 0x0f) shl 12) or (continuation() shl 6) or continuation()
                        require(value >= 0x800) { "Overlong binary XML UTF-8 sequence" }
                        append(value)
                    }
                    first and 0xf8 == 0xf0 -> {
                        val value = ((first and 0x07) shl 18) or (continuation() shl 12) or
                            (continuation() shl 6) or continuation()
                        require(value in 0x10000..0x10ffff) { "Invalid binary XML UTF-8 code point" }
                        val supplementary = value - 0x10000
                        append(0xd800 or (supplementary shr 10))
                        append(0xdc00 or (supplementary and 0x3ff))
                    }
                    else -> throw IllegalArgumentException("Invalid binary XML UTF-8 leading byte")
                }
            }
            require(output == expectedLength) { "Invalid binary XML UTF-8 string length" }
            return String(characters)
        }

        private inner class StringCursor(var position: Int, private val end: Int) {
            private fun next8(): Int {
                require(position < end) { "Truncated binary XML string length" }
                return u8(position++)
            }

            private fun next16(): Int {
                require(position.toLong() + 2 <= end) { "Truncated binary XML string length" }
                return u16(position).also { position += 2 }
            }

            fun length8(): Int {
                val first = next8()
                return if (first and 0x80 != 0) ((first and 0x7f) shl 8) or next8() else first
            }

            fun length16(): Int {
                val first = next16()
                return if (first and 0x8000 != 0) ((first and 0x7fff) shl 16) or next16() else first
            }
        }

        private fun chunk(start: Int, limit: Int): Chunk {
            require(start.toLong() + 8 <= limit) { "Truncated binary XML chunk header" }
            val headerSize = u16(start + 2)
            val size = i32(start + 4)
            require(headerSize >= 8 && size >= headerSize && start.toLong() + size <= limit) {
                "Invalid binary XML chunk bounds"
            }
            return Chunk(u16(start), start, start + headerSize, start + size)
        }

        private fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff
        private fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
        private fun i32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)
        private fun hex(value: Int): String = value.toUInt().toString(16).padStart(8, '0')
    }

    private data class Chunk(val type: Int, val start: Int, val headerEnd: Int, val end: Int)
    private data class OpenElement(
        val namespace: String?,
        val name: String,
        val attributes: List<ManifestAttribute>,
        val children: MutableList<ManifestElement> = ArrayList(),
    )
}

data class ManifestElement(
    val name: String,
    val attributes: List<ManifestAttribute>,
    val children: List<ManifestElement>,
)

data class ManifestAttribute(
    val namespaceUri: String?,
    val name: String,
    val value: String,
    val resourceId: Int? = null,
)
