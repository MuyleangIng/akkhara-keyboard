package com.akkhara.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as tests/test_abbrev.py: abbreviations never reach the model. */
class AbbreviationTest {
    private val t = mapOf("mptc" to "ក្រសួងប្រៃសណីយ៍និងទូរគមនាគមន៍", "aba" to "ធនាគារអេប៊ីអេ",
        "rupp" to "សាកលវិទ្យាល័យភូមិន្ទភ្នំពេញ")
    private val fake = mapOf("nh tver ka nv" to listOf("ខ្ញុំធ្វើការនៅ", "ខ្ញុំធ្វើការនូវ", "ខ្ញុំធ្វើកានៅ"),
        "nh bek account" to listOf("ខ្ញុំបើក account"))
    private fun top(s: String) = fake[s] ?: listOf(s)

    @Test fun aloneNeverSentToModel() =
        assertEquals(listOf("mptc", "ក្រសួងប្រៃសណីយ៍និងទូរគមនាគមន៍"),
            LiveEngine.withAbbreviations("mptc", t, 5) { listOf("WRONG") })

    @Test fun sentenceKeepsAbbreviationThenFullName() {
        val out = LiveEngine.withAbbreviations("nh tver ka nv mptc", t, 5, ::top)!!
        assertEquals("ខ្ញុំធ្វើការនៅ mptc", out[0])
        assertEquals("ខ្ញុំធ្វើការនៅក្រសួងប្រៃសណីយ៍និងទូរគមនាគមន៍", out[1])
        assertEquals("ខ្ញុំធ្វើការនូវ mptc", out[2])
    }

    @Test fun spacingCaseAndPunctuation() {
        assertEquals("ខ្ញុំបើក account ធនាគារអេប៊ីអេ", LiveEngine.withAbbreviations("nh bek account aba", t, 5, ::top)!![1])
        assertEquals(listOf("RUPP?", "សាកលវិទ្យាល័យភូមិន្ទភ្នំពេញ?"), LiveEngine.withAbbreviations("RUPP?", t, 5, ::top))
    }

    @Test fun noAbbreviationMeansNormalModel() = assertNull(LiveEngine.withAbbreviations("nh tv sala", t, 5, ::top))

    @Test fun noRepeatedWordBeforeFullName() =
        assertEquals(listOf("ធនាគារ aba", "ធនាគារអេប៊ីអេ"), LiveEngine.withAbbreviations("theanakear aba", t, 5) { listOf("ធនាគារ") })
}
