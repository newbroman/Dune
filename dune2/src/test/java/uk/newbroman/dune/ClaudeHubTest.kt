package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeHubTest {
    @Test fun parsesProjects() {
        val p = ClaudeProjects.parse("""
            # my projects
            Dune | | newbroman/Dune
            Hydrology | 019cf956-94fd-7027-a812-2bed2b53855d | https://github.com/newbroman/Newborough_Hydrology/
            Novel | https://claude.ai/project/abc
            | nothing
        """.trimIndent())
        assertEquals(3, p.size)
        assertEquals("", p[0].link); assertEquals("newbroman/Dune", p[0].repo)
        assertEquals("https://claude.ai/project/019cf956-94fd-7027-a812-2bed2b53855d", p[1].link)
        assertEquals("newbroman/Newborough_Hydrology", p[1].repo)
        assertNull(p[2].repo)
    }
}
