package app.runout

import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Source
import app.runout.soulseek.SoulseekTransfer
import app.runout.soulseek.SoulseekTransferState
import app.runout.ui.RecordCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A record sent in a Soulseek message: readable in any client, recognised by Runout. */
class RecordCardTest {
    @Test fun `a record card survives the round trip and reads as plain text elsewhere`() {
        val card = RecordCard("Air", "Moon Safari & Friends", "FLAC · 24-bit · 96 kHz")
        val text = card.encode()
        assertTrue(text.startsWith("♪ Moon Safari & Friends — Air"))
        assertEquals(card, RecordCard.parse(text))
    }

    @Test fun `a card carries the peer and folder it came from`() {
        val card = RecordCard("Air", "Moon Safari", "FLAC · 16-bit · 44.1 kHz", "dj ro", "Music/Air - Moon Safari (1998) [FLAC]")
        val text = card.encode()
        assertTrue(text.startsWith("♪ Moon Safari — Air · FLAC"))
        assertEquals(card, RecordCard.parse(text))
        // No quality: the source still reads.
        val bare = card.copy(quality = null)
        assertEquals(bare, RecordCard.parse(bare.encode()))
    }

    @Test fun `cards from older versions still read, with no source`() {
        val old = "♪ Moon Safari — Air · FLAC\nrunout:record?artist=Air&album=Moon+Safari&q=FLAC"
        assertEquals(RecordCard("Air", "Moon Safari", "FLAC"), RecordCard.parse(old))
        assertEquals(RecordCard("Air", "Moon Safari", null), RecordCard.parse("runout:record?artist=Air&album=Moon+Safari"))
    }

    @Test fun `ordinary messages are not cards`() {
        assertNull(RecordCard.parse("have you heard Moon Safari?"))
        assertNull(RecordCard.parse("runout:record?artist=&album="))
    }
}
