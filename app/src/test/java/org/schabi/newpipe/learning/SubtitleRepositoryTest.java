package org.schabi.newpipe.learning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SubtitleRepositoryTest {
    @Test
    public void parsesWebVttCuesAndTimestamps() {
        final String vtt = "WEBVTT\n\n"
                + "00:00:01.250 --> 00:00:03.500\nHello &amp; welcome.\n\n"
                + "00:01:02.000 --> 00:01:04.000 align:start\nSecond line\n";

        final List<SubtitleRepository.CaptionCue> cues =
                SubtitleRepository.parseCues(vtt);

        assertEquals(2, cues.size());
        assertEquals(1250, cues.get(0).startMs);
        assertEquals(3500, cues.get(0).endMs);
        assertEquals("Hello & welcome.", cues.get(0).text);
        assertEquals(62000, cues.get(1).startMs);
    }

    @Test
    public void parsesTtmlCuesAndMarkup() {
        final String ttml = "<tt><body><div>"
                + "<p begin=\"2.5s\" end=\"4.2s\">That&apos;s <span>ubiquitous</span>.</p>"
                + "<p begin=\"00:00:05.000\" end=\"00:00:07.000\">Next</p>"
                + "</div></body></tt>";

        final List<SubtitleRepository.CaptionCue> cues =
                SubtitleRepository.parseCues(ttml);

        assertEquals(2, cues.size());
        assertEquals(2500, cues.get(0).startMs);
        assertEquals(4200, cues.get(0).endMs);
        assertEquals("That's ubiquitous.", cues.get(0).text);
        assertEquals(5000, cues.get(1).startMs);
    }

    @Test
    public void parsesSrtCommaTimestamp() {
        assertEquals(3723456,
                SubtitleRepository.parseTimestamp("01:02:03,456"));
    }

    @Test
    public void selectsContextAroundCurrentPlaybackPosition() {
        final StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < 8; i++) {
            vtt.append(String.format("00:00:%02d.000 --> 00:00:%02d.900%nline %d%n%n",
                    i, i, i));
        }
        final List<SubtitleRepository.CaptionCue> cues =
                SubtitleRepository.parseCues(vtt.toString());
        final String context = SubtitleRepository.contextAt(cues, "", 4000);

        assertTrue(context.contains("line 2"));
        assertTrue(context.contains("line 4"));
        assertTrue(context.contains("line 6"));
        assertFalse(context.contains("line 1"));
        assertFalse(context.contains("line 7"));
    }

    @Test
    public void normalizesDictionaryLookupWords() {
        assertEquals("don't", AiLearningDialog.normalizeLookupWord("“don't,”"));
        assertEquals("COVID-19", AiLearningDialog.normalizeLookupWord("(COVID-19)"));
        assertEquals("", AiLearningDialog.normalizeLookupWord("..."));
    }
}
