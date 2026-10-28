package io.github.agitangst.ampulse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** versionCode собран по формуле anvil/docs/ANDROID.md §3 из versionName. */
class VersionCodeTest {
    private fun code(name: String): Int {
        val core = name.substringBefore('-')
        val pre = name.substringAfter('-', "").substringAfterLast('.').toIntOrNull()
        val (x, y, z) = core.split('.').map { it.toInt() }
        return (x * 10000 + y * 100 + z) * 100 + (pre ?: 99)
    }

    @Test
    fun matchesTheFormula() {
        assertEquals(code(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }

    @Test
    fun formulaExamples() {
        assertEquals(20099, code("0.2.0"))
        assertEquals(20003, code("0.2.0-beta.3"))
        assertEquals(1000099, code("1.0.0"))
    }
}
