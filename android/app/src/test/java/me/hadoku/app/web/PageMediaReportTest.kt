package me.hadoku.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The page's report is remote input: exactly the script's shape, or nothing. */
class PageMediaReportTest {
    @Test
    fun readsWhatTheScriptSends() {
        assertEquals(PageMediaReport(true, "Rain on a tin roof"), PageMediaReport.parse("""{"playing":true,"title":"Rain on a tin roof"}"""))
        assertEquals(PageMediaReport(false, ""), PageMediaReport.parse("""{"playing":false,"title":""}"""))
    }

    @Test
    fun aTitleIsOneLineAndShort() {
        val long = "x".repeat(500)
        assertEquals(PageMediaReport.MAX_TITLE, PageMediaReport.parse("""{"playing":true,"title":"$long"}""")!!.title.length)
        assertEquals("a b", PageMediaReport.parse("""{"playing":true,"title":"  a\n\tb "}""")!!.title)
        assertEquals("", PageMediaReport.parse("""{"playing":true,"title":42}""")!!.title)
    }

    @Test
    fun anythingElseIsDropped() {
        assertNull(PageMediaReport.parse(null))
        assertNull(PageMediaReport.parse("pause"))
        assertNull(PageMediaReport.parse("""{"title":"no state"}"""))
        assertNull(PageMediaReport.parse("""{"playing":"yes"}"""))
        assertNull(PageMediaReport.parse("[true]"))
    }
}
