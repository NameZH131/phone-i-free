package com.phoneinteraction.free.service

import org.junit.Assert.assertEquals
import org.junit.Test

// Keyword file parsing is pure text logic: only display words after @ are valid; lines without @
// and empty words are skipped; duplicates are removed. The keyword file itself lives in assets
// (reading it needs an Android environment), so only the text contract of parse() is tested here.
class VoiceKeywordsParseTest {

    @Test
    fun extractsDisplayWordAfterAtSign() {
        assertEquals(listOf("上一个"), VoiceKeywords.parse("shang yi ge @上一个"))
    }

    @Test
    fun skipsLinesWithoutAtSignAndEmptyWordsAndDeduplicates() {
        val text = "shang yi ge @上一个\n\nxia yi ge @下一个\nzz @\nsil\n@上一个"
        assertEquals(listOf("上一个", "下一个"), VoiceKeywords.parse(text))
    }
}
