package uk.newbroman.dune
import org.junit.Assert.assertEquals
import org.junit.Test
class RepoTasksTest {
    @Test fun checkboxes() {
        val md = "# Tasks\n- [ ] Rebuild PDFs\n  * [x] Fix figure 3\n+ [X] Done thing\n- not a task\n- [] nope\n1. [ ] numbered isn't matched"
        assertEquals(listOf("Rebuild PDFs" to false, "Fix figure 3" to true, "Done thing" to true), RepoTasks.parse(md))
    }
}
