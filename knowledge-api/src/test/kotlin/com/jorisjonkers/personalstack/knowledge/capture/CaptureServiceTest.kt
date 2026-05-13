package com.jorisjonkers.personalstack.knowledge.capture

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.queue.IngestPublisher
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CaptureServiceTest {
    private val noteRepository = mockk<NoteRepository>()
    private val ingestPublisher = mockk<IngestPublisher>(relaxed = true)
    private val service = CaptureService(noteRepository, ingestPublisher)

    private val baseRequest =
        CaptureRequest(
            type = KbNoteType.LESSON,
            scope = "personal",
            source = "claude-code",
            title = "title",
            body = "body",
            tags = setOf("kotlin"),
        )

    @Test
    fun `captureLesson mints a ULID, forces type=lesson, persists, then publishes in that order`() {
        every { noteRepository.create(any()) } answers { firstArg() }

        val note = service.captureLesson(baseRequest.copy(type = KbNoteType.NOTE))

        assertThat(note.type).isEqualTo(KbNoteType.LESSON)
        assertThat(note.id).matches("[0-9A-HJKMNP-TV-Z]{26}")
        assertThat(note.tags).containsExactly("kotlin")

        verifyOrder {
            noteRepository.create(match { it.type == KbNoteType.LESSON })
            ingestPublisher.publishCapturedNote(match { it.id == note.id && it.type == KbNoteType.LESSON })
        }
    }

    @Test
    fun `captureDecision overrides the request type to DECISION`() {
        every { noteRepository.create(any()) } answers { firstArg() }

        val note = service.captureDecision(baseRequest)

        assertThat(note.type).isEqualTo(KbNoteType.DECISION)
        verify { ingestPublisher.publishCapturedNote(match { it.type == KbNoteType.DECISION }) }
    }

    @Test
    fun `captureGenericNote honours the request type`() {
        every { noteRepository.create(any()) } answers { firstArg() }

        val note = service.captureGenericNote(baseRequest.copy(type = KbNoteType.FACT))

        assertThat(note.type).isEqualTo(KbNoteType.FACT)
        verify { ingestPublisher.publishCapturedNote(match { it.type == KbNoteType.FACT }) }
    }

    @Test
    fun `vault_path defaults to scope-typed draft path when not provided`() {
        every { noteRepository.create(any()) } answers { firstArg() }

        val note = service.captureLesson(baseRequest.copy(vaultPath = null))

        assertThat(note.vaultPath).isEqualTo("personal/lesson/draft.md")
    }

    @Test
    fun `explicit vault_path is preserved verbatim`() {
        every { noteRepository.create(any()) } answers { firstArg() }

        val note = service.captureLesson(baseRequest.copy(vaultPath = "personal/lesson/custom.md"))

        assertThat(note.vaultPath).isEqualTo("personal/lesson/custom.md")
    }

    @Test
    fun `the captured note returned by the service matches what was persisted`() {
        val captured = slot<KbNote>()
        every { noteRepository.create(capture(captured)) } answers { firstArg() }

        val note = service.captureLesson(baseRequest)

        assertThat(captured.captured).isEqualTo(note)
    }

    private inline fun <reified T : Any> slot(): io.mockk.CapturingSlot<T> = io.mockk.slot()
}
