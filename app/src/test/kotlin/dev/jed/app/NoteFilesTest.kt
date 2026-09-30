package dev.jed.app

import dev.jed.app.notes.NoteFiles
import dev.jed.app.notes.NoteMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFilesTest {

    @Test
    fun slugIsSafe() {
        assertEquals("deploy-notes", NoteFiles.slugOf("Deploy Notes!"))
        assertEquals("untitled", NoteFiles.slugOf("!!!"))
        assertEquals("a-b", NoteFiles.slugOf("a   b"))
    }

    @Test
    fun uniqueNameSkipsTakenCaseInsensitively() {
        assertEquals("a.md", NoteFiles.uniqueName("a", emptySet()))
        assertEquals("a-2.md", NoteFiles.uniqueName("a", setOf("A.md")))
        assertEquals("a-3.md", NoteFiles.uniqueName("a", setOf("a.md", "a-2.md")))
    }

    @Test
    fun titlePrefersH1() {
        assertEquals("Hi", NoteFiles.titleOf("# Hi\nbody"))
        assertEquals("body", NoteFiles.titleOf("body\nmore"))
        assertEquals("Untitled", NoteFiles.titleOf("\n"))
    }

    @Test
    fun frontmatterParses() {
        val (fm, body) = NoteFiles.splitFrontmatter("---\ncwd: proj\nenv:\n  K: v\nprofile: deploy\ntags: a, b\ntemplate: true\n---\nhello")
        assertEquals("proj", fm.cwd)
        assertEquals(mapOf("K" to "v"), fm.env)
        assertEquals("deploy", fm.profile)
        assertEquals(listOf("a", "b"), fm.tags)
        assertTrue(fm.template)
        assertEquals("hello", body)
    }

    @Test
    fun fencesParseInOrder() {
        val blocks = NoteFiles.parseFences("# t\n```sh\necho hi\n```\ntext\n```python\nprint(1)\n```")
        assertEquals(2, blocks.size)
        assertEquals("sh", blocks[0].lang)
        assertEquals("echo hi\n", blocks[0].code)
        assertEquals("python", blocks[1].lang)
    }

    @Test
    fun runnerMapping() {
        assertEquals(NoteFiles.Runner.SHELL, NoteFiles.runnerFor("sh"))
        assertEquals(NoteFiles.Runner.SHELL, NoteFiles.runnerFor("Bash"))
        assertEquals(NoteFiles.Runner.PYTHON, NoteFiles.runnerFor("py"))
        assertNull(NoteFiles.runnerFor("node"))
        assertNull(NoteFiles.runnerFor("prompt"))
    }

    @Test
    fun tagsSkipHeadings() {
        val tags = NoteFiles.tagsOf("# Title #notatag\nDo #deploy now (#later).")
        assertTrue("deploy" in tags)
        assertTrue("later" in tags)
        assertTrue(tags.none { it == "notatag" || it.startsWith("Title") })
    }

    @Test
    fun wikilinksResolveByTitle() {
        val notes = listOf(
            NoteMeta("a.md", "Deploy", "", 2L),
            NoteMeta("b.md", "deploy", "", 5L),
            NoteMeta("c.md", "Other", "", 9L),
        )
        assertEquals("b.md", NoteFiles.resolveTitle("deploy", notes)?.id)
        assertEquals("c.md", NoteFiles.resolveTitle("Oth", notes)?.id)
        assertNull(NoteFiles.resolveTitle("missing", notes))
    }

    @Test
    fun searchFindsTitleAndBody() {
        val notes = listOf(NoteMeta("a.md", "Deploy", "", 1L), NoteMeta("b.md", "Other", "", 2L))
        val bodies = mapOf("a.md" to "run it", "b.md" to "deploy here")
        val hits = NoteFiles.search(notes, bodies, "deploy")
        assertEquals(2, hits.size)
        assertEquals("b.md", hits[0].id) // newest first
    }

    @Test
    fun folderGuard() {
        assertNull(NoteFiles.folderProblem(""))
        assertNull(NoteFiles.folderProblem("proj/api"))
        assertTrue(NoteFiles.folderProblem("../x") != null)
        assertTrue(NoteFiles.folderProblem(".hidden") != null)
    }
}
