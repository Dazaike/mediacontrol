package com.mediacontrol.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RankingTest {

    private fun cand(
        pkg: String,
        label: String = pkg,
        playing: Boolean = false,
        updated: Long = 0L,
    ) = SessionCandidate(pkg, label, playing, updated)

    @Test
    fun `playing session wins over paused regardless of recency`() {
        val top = pickTopSession(
            listOf(
                cand("paused.app", updated = 9_000L),
                cand("playing.app", playing = true, updated = 100L),
            ),
            lastPackage = null,
        )
        assertEquals("playing.app", top?.packageName)
    }

    @Test
    fun `newest position update wins within same playing state`() {
        val top = pickTopSession(
            listOf(
                cand("old.app", playing = true, updated = 100L),
                cand("new.app", playing = true, updated = 9_000L),
            ),
            lastPackage = null,
        )
        assertEquals("new.app", top?.packageName)
    }

    @Test
    fun `saved package breaks recency ties`() {
        val top = pickTopSession(
            listOf(
                cand("other.app", updated = 500L),
                cand("saved.app", updated = 500L),
            ),
            lastPackage = "saved.app",
        )
        assertEquals("saved.app", top?.packageName)
    }

    @Test
    fun `alphabetical appLabel is the final tiebreak`() {
        val top = pickTopSession(
            listOf(
                cand("z.app", label = "Zulu", updated = 500L),
                cand("a.app", label = "Alpha", updated = 500L),
            ),
            lastPackage = null,
        )
        assertEquals("a.app", top?.packageName)
    }

    @Test
    fun `empty list yields null`() {
        assertNull(pickTopSession(emptyList(), "saved.app"))
    }
}
