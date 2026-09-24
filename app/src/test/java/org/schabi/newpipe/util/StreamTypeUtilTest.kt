/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType

class StreamTypeUtilTest {
    @Test
    fun `all live stream variants are filtered`() {
        assertTrue(StreamTypeUtil.isAnyLiveStream(StreamType.LIVE_STREAM))
        assertTrue(StreamTypeUtil.isAnyLiveStream(StreamType.AUDIO_LIVE_STREAM))
        assertTrue(StreamTypeUtil.isAnyLiveStream(StreamType.POST_LIVE_STREAM))
        assertTrue(StreamTypeUtil.isAnyLiveStream(StreamType.POST_LIVE_AUDIO_STREAM))
    }

    @Test
    fun `regular streams are retained`() {
        assertFalse(StreamTypeUtil.isAnyLiveStream(StreamType.VIDEO_STREAM))
        assertFalse(StreamTypeUtil.isAnyLiveStream(StreamType.AUDIO_STREAM))
    }
}
