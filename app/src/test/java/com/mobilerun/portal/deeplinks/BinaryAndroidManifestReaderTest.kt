package com.mobilerun.portal.deeplinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAndroidManifestReaderTest {
    @Test
    fun readsManifestCompiledByAndroidAapt2IncludingNamespacesAliasesAndResourceReferences() {
        val manifest = BinaryAndroidManifestReader.read(fixture())

        assertEquals("manifest", manifest.name)
        assertEquals("com.mobilerun.fixture.links", manifest.attribute("package", null).value)
        assertEquals("42", manifest.attribute("versionCode").value)
        val application = manifest.children.single { it.name == "application" }
        assertEquals("Fixture Café 📱", application.attribute("label").value)
        assertEquals("true", application.attribute("enabled").value)
        val activity = application.children.first { it.name == "activity" }
        assertEquals(".LinkActivity", activity.attribute("name").value)
        assertEquals("true", activity.attribute("exported").value)
        val filter = activity.children.single { it.name == "intent-filter" }
        assertEquals("-7", filter.attribute("priority").value)
        assertEquals("true", filter.attribute("autoVerify").value)
        assertEquals(
            listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            filter.children.filter { it.name == "category" }.map { it.attribute("name").value },
        )
        val data = filter.children.filter { it.name == "data" }
        assertEquals("fixture", data[0].attribute("scheme").value)
        assertEquals("/orders/", data[0].attribute("pathPrefix").value)
        assertEquals("例え.example", data[1].attribute("host").value)
        assertEquals("/products/.*", data[1].attribute("pathPattern").value)
        assertEquals(0x7f010001, data[2].attribute("scheme").resourceId)
        assertEquals("@0x7f010001", data[2].attribute("scheme").value)
        assertEquals(0x7f010000, data[2].attribute("host").resourceId)
        assertNull(data[0].attribute("scheme").resourceId)
        val hidden = application.children.filter { it.name == "activity" }[1]
        assertEquals("false", hidden.attribute("enabled").value)
        assertEquals("false", hidden.attribute("exported").value)
        val alias = application.children.single { it.name == "activity-alias" }
        assertEquals(".LinkActivity", alias.attribute("targetActivity").value)
        assertEquals("com.example.PERMISSION", alias.attribute("permission").value)
        // Unrelated manifest values must not stop discovery of deep links.
        val layout = activity.children.single { it.name == "layout" }
        assertEquals("0x00000011", layout.attribute("gravity").value)
        assertEquals("300.0dip", layout.attribute("defaultWidth").value)
        assertTrue(layout.attribute("defaultHeight").value.endsWith("%"))
    }

    @Test
    fun utf8AndUtf16CompiledPoolsProduceTheSameApplicationTree() {
        val utf16 = fixture()
        val utf8 = fixture("manifest-aapt2-utf8.axml")
        assertEquals(0, intAt(utf16, 24) and 0x100)
        assertEquals(0x100, intAt(utf8, 24) and 0x100)
        val expected = BinaryAndroidManifestReader.read(utf16).children.single { it.name == "application" }
        val actual = BinaryAndroidManifestReader.read(utf8).children.single { it.name == "application" }
        assertEquals(expected, actual)
        // Two-byte UTF-8 pool lengths count UTF-16 code units separately from encoded bytes.
        assertEquals("é".repeat(150), actual.children.single { it.name == "meta-data" }.attribute("value").value)
    }

    @Test
    fun acceptsOrdinaryFourByteUtf8AlongsideAapt2Cesu8() {
        val bytes = fixture("manifest-aapt2-utf8.axml")
        val prefix = "Fixture Café ".toByteArray(Charsets.UTF_8)
        val label = findBytes(bytes, prefix)
        // Replace AAPT2's six-byte CESU-8 surrogate pair with four-byte UTF-8 plus two ASCII
        // characters. The encoded length stays the same; the UTF-16 character count grows by two.
        byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x93.toByte(), 0xb1.toByte(), 'x'.code.toByte(), 'x'.code.toByte())
            .copyInto(bytes, label + prefix.size)
        bytes[label - 2] = 17
        val application = BinaryAndroidManifestReader.read(bytes).children.single { it.name == "application" }
        assertEquals("Fixture Café 📱xx", application.attribute("label").value)
    }

    @Test
    fun rejectsInvalidUtf8AndMissingStringTerminators() {
        val bytes = fixture("manifest-aapt2-utf8.axml")
        val label = findBytes(bytes, "Fixture Café ".toByteArray(Charsets.UTF_8))
        rejects(bytes.copyOf().apply { this[label] = 0xff.toByte() })
        rejects(bytes.copyOf().apply { this[label + 20] = 1 })
        rejects(bytes.copyOf().apply { this[label - 2] = 14 })
    }

    @Test
    fun rejectsPlainTextAndMissingRoot() {
        rejects("<manifest package=\"example\"/>".toByteArray())
        rejects(byteArrayOf(3, 0, 8, 0, 8, 0, 0, 0))
    }

    @Test
    fun rejectsOversizedInputBeforeDecoding() {
        val error = rejects(ByteArray(BinaryAndroidManifestReader.MAX_BYTES + 1))
        assertTrue(error.message.orEmpty().contains("size"))
    }

    @Test
    fun rejectsTruncatedAndInconsistentChunkBounds() {
        val bytes = fixture()
        rejects(bytes.copyOf(7))
        rejects(bytes.copyOf(bytes.size - 1))
        rejects(bytes.copyOf().apply { putInt(4, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { putInt(12, 0) })
        rejects(bytes.copyOf().apply { putInt(12, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { this[10] = 7; this[11] = 0 })
    }

    @Test
    fun rejectsStringPoolCountsAndOffsetsOutsideItsChunk() {
        val bytes = fixture()
        rejects(bytes.copyOf().apply { putInt(16, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { putInt(16, -1) })
        rejects(bytes.copyOf().apply { putInt(28, 0) })
        rejects(bytes.copyOf().apply { putInt(36, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { putInt(36, -1) })
    }

    @Test
    fun rejectsInvalidNodeStringIndicesAndAttributeSpans() {
        val bytes = fixture()
        val start = chunks(bytes).first { it.type == START_ELEMENT }.start
        rejects(bytes.copyOf().apply { putInt(start + 20, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { this[start + 26] = 19; this[start + 27] = 0 })
        rejects(bytes.copyOf().apply { this[start + 28] = 0xff.toByte(); this[start + 29] = 0xff.toByte() })
        val attribute = start + 16 + 20
        rejects(bytes.copyOf().apply { putInt(attribute + 4, Int.MAX_VALUE) })
        rejects(bytes.copyOf().apply { this[attribute + 12] = 7 })
    }

    @Test
    fun rejectsMissingMismatchedAndExtraClosingElements() {
        val bytes = fixture()
        val chunks = chunks(bytes)
        val end = chunks.last { it.type == END_ELEMENT }
        rejects(bytes.copyOf().apply { putInt(end.start + 20, -1) })
        rejects(withChunks(bytes, chunks.filterNot { it == end }))
        rejects(withChunks(bytes, chunks + end))
    }

    @Test
    fun rejectsMultipleRoots() {
        val bytes = fixture()
        val chunks = chunks(bytes)
        val start = chunks.first { it.type == START_ELEMENT }
        val end = chunks.last { it.type == END_ELEMENT }
        rejects(withChunks(bytes, chunks + start + end))
    }

    @Test
    fun enforcesDepthLimitUsingRealCompiledElementChunks() {
        val bytes = fixture()
        val chunks = chunks(bytes)
        val leafStartIndex = chunks.indexOfFirst { it.type == START_ELEMENT && chunks.getOrNull(chunks.indexOf(it) + 1)?.type == END_ELEMENT }
        val leafStart = chunks[leafStartIndex]
        val leafEnd = chunks[leafStartIndex + 1]
        val prefix = chunks.takeWhile { it.type != START_ELEMENT }
        val nested = withChunks(bytes, prefix + List(129) { leafStart } + List(129) { leafEnd })
        val error = rejects(nested)
        assertTrue(error.message.orEmpty().contains("limit"))
    }

    @Test
    fun enforcesNodeLimitUsingRealCompiledElementChunks() {
        val bytes = fixture()
        val chunks = chunks(bytes)
        val root = chunks.first { it.type == START_ELEMENT }
        val rootEnd = chunks.last { it.type == END_ELEMENT }
        val leafIndex = chunks.indexOfFirst { it.type == START_ELEMENT && chunks.getOrNull(chunks.indexOf(it) + 1)?.type == END_ELEMENT }
        val prefix = chunks.takeWhile { it.type != START_ELEMENT }
        val siblings = List(10_000) { listOf(chunks[leafIndex], chunks[leafIndex + 1]) }.flatten()
        val error = rejects(withChunks(bytes, prefix + root + siblings + rootEnd))
        assertTrue(error.message.orEmpty().contains("limit"))
    }

    @Test
    fun rejectsEveryTruncatedChunkWithAnAdjustedDocumentSize() {
        val bytes = fixture()
        chunks(bytes).forEach { chunk ->
            // An incomplete chunk body must fail even if the outer document claims it is complete.
            if (chunk.end - chunk.start > 8) {
                val truncated = bytes.copyOf(chunk.end - 1).apply { putInt(4, size) }
                rejects(truncated)
            }
        }
    }

    private fun ManifestElement.attribute(name: String, namespace: String? = ANDROID): ManifestAttribute =
        attributes.single { it.name == name && it.namespaceUri == namespace }

    private fun fixture(name: String = "manifest-aapt2.axml"): ByteArray {
        val stream = javaClass.getResourceAsStream("/deeplinks/$name")
        assertNotNull("Missing compiled Android binary XML fixture: $name", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun rejects(bytes: ByteArray): IllegalArgumentException =
        assertThrows(IllegalArgumentException::class.java) { BinaryAndroidManifestReader.read(bytes) }

    private fun chunks(bytes: ByteArray): List<TestChunk> {
        val result = ArrayList<TestChunk>()
        var offset = 8
        while (offset < bytes.size) {
            val size = intAt(bytes, offset + 4)
            val type = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            result += TestChunk(type, offset, offset + size)
            offset += size
        }
        assertEquals(bytes.size, offset)
        return result
    }

    private fun withChunks(original: ByteArray, chunks: List<TestChunk>): ByteArray {
        val size = 8 + chunks.sumOf { it.end - it.start }
        return ByteArray(size).apply {
            original.copyInto(this, endIndex = 8)
            putInt(4, size)
            var cursor = 8
            chunks.forEach {
                original.copyInto(this, cursor, it.start, it.end)
                cursor += it.end - it.start
            }
        }
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, shift -> value or ((bytes[offset + shift].toInt() and 0xff) shl (shift * 8)) }

    private fun findBytes(bytes: ByteArray, pattern: ByteArray): Int {
        val index = (0..bytes.size - pattern.size).firstOrNull { start ->
            pattern.indices.all { bytes[start + it] == pattern[it] }
        }
        return requireNotNull(index) { "Encoded test fixture string is missing" }
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private data class TestChunk(val type: Int, val start: Int, val end: Int)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val START_ELEMENT = 0x0102
        const val END_ELEMENT = 0x0103
    }
}
