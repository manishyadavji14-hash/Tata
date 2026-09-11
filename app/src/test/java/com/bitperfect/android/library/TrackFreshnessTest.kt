package com.bitperfect.android.library

import com.bitperfect.android.library.scanner.TrackFreshness
import com.bitperfect.android.library.scanner.TrackFreshness.FileFacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The rules that decide whether the library still describes the file on disk.
 *
 * These exist because the scan used to answer that question by comparing the media
 * index against itself — the stored size and modification time had been copied from
 * the index on the previous scan, so "has this file changed" was really "has the index
 * changed its mind", which a stale index never does. A file replaced in place kept its
 * old row permanently, and rescanning could not dislodge it.
 */
class TrackFreshnessTest {

    @Nested
    @DisplayName("Is the stored library row still accurate")
    inner class RowCurrency {

        @Test
        @DisplayName("A row matching the file on disk is current")
        fun matchingRowIsCurrent() {
            assertTrue(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 138_412_032L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_000L)
                )
            )
        }

        @Test
        @DisplayName("A file of a different size means the row describes a different file")
        fun differentSizeIsStale() {
            // The reported case: the library held a 4:39 / 48 kHz entry while the file
            // on disk was the 132 MB one the decoder read as 192 kHz / 7:05.
            assertFalse(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 84_000_000L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_000L)
                )
            )
        }

        @Test
        @DisplayName("A file rewritten at the same size is still caught by its timestamp")
        fun sameSizeButRewrittenIsStale() {
            assertFalse(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 138_412_032L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_600_000L)
                )
            )
        }

        @Test
        @DisplayName("Second-granularity timestamps from older rows are tolerated")
        fun secondGranularityIsTolerated() {
            // Rows written before this change hold MediaStore's whole-second time.
            // Treating that as a change would re-read the entire library every scan.
            assertTrue(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 138_412_032L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_999L)
                )
            )
        }

        @Test
        @DisplayName("An unreadable file leaves the row alone rather than re-reading it")
        fun unreadableFileIsAssumedCurrent() {
            assertTrue(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 138_412_032L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = null
                )
            )
            // A zero-length stat is not evidence either.
            assertTrue(
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = 138_412_032L,
                    storedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(0L, 0L)
                )
            )
        }
    }

    @Nested
    @DisplayName("Can the media index's own numbers be believed")
    inner class IndexCurrency {

        @Test
        @DisplayName("An index agreeing with the file is believed")
        fun agreeingIndexIsCurrent() {
            assertTrue(
                TrackFreshness.isIndexCurrent(
                    indexedSizeBytes = 138_412_032L,
                    indexedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_000L)
                )
            )
        }

        @Test
        @DisplayName("An index with the wrong size is not evidence about the file")
        fun staleIndexIsNotCurrent() {
            // This is what makes the scan probe the file instead of copying the
            // index's confident-but-wrong sample rate and bit depth into the library.
            assertFalse(
                TrackFreshness.isIndexCurrent(
                    indexedSizeBytes = 84_000_000L,
                    indexedModifiedMs = 1_700_000_000_000L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_000L)
                )
            )
        }

        @Test
        @DisplayName("An index reporting nothing is not called stale")
        fun blankIndexIsNotCalledStale() {
            // Below Android 12 the index reports no size for some entries; that is
            // missing information, not a contradiction.
            assertTrue(
                TrackFreshness.isIndexCurrent(
                    indexedSizeBytes = 0L,
                    indexedModifiedMs = 0L,
                    onDisk = FileFacts(138_412_032L, 1_700_000_000_000L)
                )
            )
        }
    }

    @Nested
    @DisplayName("Never overwrite a known figure with nothing")
    inner class PreferMeasured {

        @Test
        @DisplayName("What the file yielded wins over the index and the stored row")
        fun measuredWins() {
            assertEquals(192_000, TrackFreshness.preferMeasured(192_000, 48_000, 44_100))
        }

        @Test
        @DisplayName("The index is used when the file could not be read")
        fun indexedIsSecond() {
            assertEquals(48_000, TrackFreshness.preferMeasured(0, 48_000, 44_100))
        }

        @Test
        @DisplayName("The stored value survives when neither source has one")
        fun storedIsKept() {
            // The rule the artwork path already had and this one did not: a scan that
            // reads nothing must not empty a field that was previously correct.
            assertEquals(44_100, TrackFreshness.preferMeasured(0, 0, 44_100))
            assertEquals(24, TrackFreshness.preferMeasured(0, 0, 24))
        }

        @Test
        @DisplayName("Zero is only the answer when nothing is known at all")
        fun zeroOnlyWhenNothingKnown() {
            assertEquals(0, TrackFreshness.preferMeasured(0, 0, 0))
        }

        @Test
        @DisplayName("Durations follow the same order of preference")
        fun durationsFollowTheSameRule() {
            assertEquals(425_000L, TrackFreshness.preferMeasuredDuration(425_000L, 279_000L, 1L))
            assertEquals(279_000L, TrackFreshness.preferMeasuredDuration(0L, 279_000L, 1L))
            assertEquals(1L, TrackFreshness.preferMeasuredDuration(0L, 0L, 1L))
            assertEquals(0L, TrackFreshness.preferMeasuredDuration(0L, 0L, 0L))
        }
    }
}
