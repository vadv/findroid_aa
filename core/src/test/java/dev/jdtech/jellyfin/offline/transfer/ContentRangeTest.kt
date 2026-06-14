package dev.jdtech.jellyfin.offline.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {
    @Test
    fun parses_complete_range_with_known_total() {
        val parsed = ContentRange.parse("bytes 100-499/500")
        assertEquals(100L, parsed?.start)
        assertEquals(499L, parsed?.end)
        assertEquals(500L, parsed?.total)
    }

    @Test
    fun parses_range_with_unknown_total() {
        val parsed = ContentRange.parse("bytes 0-9/*")
        assertEquals(0L, parsed?.start)
        assertEquals(9L, parsed?.end)
        assertNull(parsed?.total)
    }

    @Test
    fun trims_surrounding_whitespace_and_handles_uppercase_unit() {
        val parsed = ContentRange.parse("  BYTES 1-2/3  ")
        assertEquals(1L, parsed?.start)
        assertEquals(2L, parsed?.end)
        assertEquals(3L, parsed?.total)
    }

    @Test
    fun returns_null_for_malformed_header() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("items 1-2/3"))
        assertNull(ContentRange.parse("bytes foo/3"))
        assertNull(ContentRange.parse("bytes 1-2"))
    }
}
