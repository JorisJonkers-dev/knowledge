package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.RecallRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecallServiceTest {
    private val noteRepository = mockk<NoteRepository>()
    private val recallRepository = mockk<RecallRepository>()
    private val service = RecallService(noteRepository, recallRepository)

    @Test
    fun `recall delegates to the recall repository with the same args`() {
        every { recallRepository.recall("rockets", "personal", 5) } returns
            listOf(
                RecallHit(
                    id = "01HXYZ",
                    type = "lesson",
                    scope = "personal",
                    title = "t",
                    snippet = "s",
                    score = 0.4,
                ),
            )

        val hits = service.recall("rockets", "personal", 5)

        assertThat(hits).hasSize(1)
        verify(exactly = 1) { recallRepository.recall("rockets", "personal", 5) }
    }

    @Test
    fun `getNote delegates by id`() {
        every { noteRepository.findById("01HXYZ") } returns null
        assertThat(service.getNote("01HXYZ")).isNull()
        verify(exactly = 1) { noteRepository.findById("01HXYZ") }
    }

    @Test
    fun `listRecent passes scope, type, and limit through unchanged`() {
        every { noteRepository.listRecent("work", KbNoteType.DECISION, 7) } returns emptyList()
        service.listRecent("work", KbNoteType.DECISION, 7)
        verify(exactly = 1) { noteRepository.listRecent("work", KbNoteType.DECISION, 7) }
    }

    @Test
    fun `findConflicts delegates by id`() {
        every { noteRepository.findConflicts("01HXYZ") } returns emptyList()
        service.findConflicts("01HXYZ")
        verify(exactly = 1) { noteRepository.findConflicts("01HXYZ") }
    }
}
