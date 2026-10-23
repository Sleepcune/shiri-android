package com.hubra.app.ai

import java.io.ByteArrayInputStream
import org.junit.Test
import kotlin.test.*

class BoundedInputStreamTest {
    @Test fun exactLimitAndUtf8CanBeRead() {
        val text="番茄炒蛋"; val bytes=text.toByteArray()
        assertEquals(text,BoundedInputStream(ByteArrayInputStream(bytes),bytes.size.toLong()).reader().readText())
    }
    @Test fun oversizedResponseFailsBeforeParsingIncludingSingleHugeSseLine() {
        val bytes=("data: "+"a".repeat(10000)).toByteArray()
        assertFailsWith<IllegalArgumentException> { BoundedInputStream(ByteArrayInputStream(bytes),100).reader().buffered().readLine() }
        assertFailsWith<IllegalArgumentException> { BoundedInputStream(ByteArrayInputStream(bytes),3).readBytes() }
    }
}
