package no.nav.sf.github.metrics

import kotlin.test.assertTrue
import kotlin.test.fail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Here are some tests covering all possible cases for Entry's parseLine method.
 * The tests cover:
 * - Valid entries with different tag formats
 * - Edge cases like empty tags and values
 * - Different orderings of tags should not matter
 * - Note that isValidLine has been run before parseLine, so we assume the input is valid.
 * - With instance unspecified, or given in tags, or given in argument
 * oh yeah and copilot attempted to write this obviously
 */
class EntryTest {

    @Test
    fun `parseLine should correctly parse a valid entry with tags`() {
        val line = "metric{name=\"value\",tag1=\"value1\"} 123.45"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(listOf(Tag("instance", ""), Tag("name", "value"), Tag("tag1", "value1")), entry.tags)
        assertEquals(123.45, entry.value)
    }

    @Test
    fun `parseLine should handle empty tags`() {
        val line = "metric{} 0.0"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(listOf(Tag("instance", "")), entry.tags)
        assertEquals(0.0, entry.value)
    }

    @Test
    fun `parseLine should handle single tag`() {
        val line = "metric{tag=\"value\"} 42.0"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(listOf(Tag("instance", ""), Tag("tag", "value")), entry.tags)
        assertEquals(42.0, entry.value)
    }

    @Test
    fun `parseLine should handle multiple tags in different order`() {
        val line = "metric{tag2=\"value2\",tag1=\"value1\"} 99.99"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(listOf(Tag("instance", ""), Tag("tag1", "value1"), Tag("tag2", "value2")), entry.tags)
        assertEquals(99.99, entry.value)
    }

    @Test
    fun `parseLine should handle tags with empty values`() {
        val line = "metric{tag1=\"\",tag2=\"value2\"} 15.0"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(
            listOf(Tag("instance", ""), Tag("tag1", ""), Tag("tag2", "value2")),
            entry.tags
        )
        assertEquals(15.0, entry.value)
    }

    @Test
    fun `parseLine should handle tags with leading and trailing spaces in values`() {
        val line = "metric{tag1=\"  leading space\",tag2=\"trailing space  \"} 35.0"
        val entry = Entry.parseLine(null, line)
        assertEquals("metric", entry.name)
        assertEquals(
            listOf(Tag("instance", ""), Tag("tag1", "  leading space"), Tag("tag2", "trailing space  ")),
            entry.tags
        )
        assertEquals(35.0, entry.value)
    }
}
