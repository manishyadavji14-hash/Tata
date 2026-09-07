package com.bitperfect.android.library

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The on-disk artwork cache, and in particular writing to it from two threads.
 *
 * Reported symptom: album art appeared sometimes, and sometimes in the app but not
 * on the lock screen. A track change asks for the same cover twice at once — the
 * player resolves details for the screen while the playback service resolves them
 * for the notification — and the temporary file used to be named after the target,
 * so both writes shared one path. They interleaved into a corrupt image, or one
 * renamed the temporary away and the other's rename then failed and reported no
 * artwork at all.
 *
 * [concurrentWritesOfTheSameCoverAllSucceed] is the test that would have caught it.
 */
@DisplayName("ArtworkCache Tests")
class ArtworkCacheTest {

    private fun sourceFile(directory: File, name: String = "song.flac"): File =
        File(directory, name).apply { writeBytes(ByteArray(2_048) { it.toByte() }) }

    private fun cover(size: Int = 4_096, fill: Byte = 7): ByteArray = ByteArray(size) { fill }

    // --- The race ---

    @Test
    @DisplayName("two threads caching the same cover at once both get a valid image")
    fun concurrentWritesOfTheSameCoverAllSucceed(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)
        val bytes = cover()

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())

        repeat(threads) {
            pool.submit {
                try {
                    start.await()
                    results.add(cache.put(source, bytes))
                } catch (error: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "writes did not finish")

        assertEquals(0, failures.get(), "a write threw")
        assertEquals(threads, results.size)
        results.forEach { path ->
            assertNotNull(path, "a concurrent write reported no artwork")
            val cached = File(path!!)
            assertTrue(cached.isFile, "cached file is missing: $path")
            assertEquals(
                bytes.size.toLong(),
                cached.length(),
                "cached image is truncated — writes interleaved"
            )
        }

        // All of them describe the same entry, and its contents are intact.
        assertEquals(1, results.toSet().size, "the same source produced different entries")
        assertTrue(File(results.first()!!).readBytes().contentEquals(bytes))
    }

    @Test
    @DisplayName("no temporary files are left behind")
    fun noTemporariesLeftBehind(@TempDir directory: File) {
        val cacheDir = File(directory, "cache")
        val cache = ArtworkCache(cacheDir)

        repeat(4) { index ->
            cache.put(sourceFile(directory, "song$index.flac"), cover(fill = index.toByte()))
        }

        val leftovers = cacheDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftovers.isEmpty(), "temporary files remain: ${leftovers.map { it.name }}")
    }

    // --- Basic behaviour ---

    @Test
    @DisplayName("a cached cover is found again")
    fun putThenFind(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)

        val path = cache.put(source, cover())

        assertNotNull(path)
        assertEquals(path, cache.find(source))
    }

    @Test
    @DisplayName("nothing is found for a source that was never cached")
    fun findMiss(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))

        assertNull(cache.find(sourceFile(directory)))
    }

    @Test
    @DisplayName("empty artwork is refused rather than cached as a zero-length file")
    fun emptyBytesRefused(@TempDir directory: File) {
        // A zero-length entry would later read as a valid cover and show as blank.
        val cache = ArtworkCache(File(directory, "cache"))

        assertNull(cache.put(sourceFile(directory), ByteArray(0)))
    }

    @Test
    @DisplayName("editing the source file invalidates its cached cover")
    fun retaggingInvalidates(@TempDir directory: File) {
        // The key includes size and modification time, so a re-tagged file misses
        // instead of keeping the old art forever.
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)
        cache.put(source, cover())

        source.writeBytes(ByteArray(9_000) { 3 })

        assertNull(cache.find(source))
    }

    @Test
    @DisplayName("different sources get different entries")
    fun perSourceEntries(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))
        val first = sourceFile(directory, "first.flac")
        val second = sourceFile(directory, "second.flac")

        val firstPath = cache.put(first, cover(size = 1_024, fill = 1))
        val secondPath = cache.put(second, cover(size = 2_048, fill = 2))

        assertTrue(firstPath != secondPath)
        assertEquals(1_024L, File(firstPath!!).length())
        assertEquals(2_048L, File(secondPath!!).length())
    }

    @Test
    @DisplayName("re-caching identical bytes reuses the entry")
    fun identicalBytesShortCircuit(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)
        val bytes = cover()

        val first = cache.put(source, bytes)
        val second = cache.put(source, bytes)

        assertEquals(first, second)
    }

    @Test
    @DisplayName("clearing removes every cached cover")
    fun clearEmptiesTheCache(@TempDir directory: File) {
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)
        cache.put(source, cover())

        cache.clear()

        assertNull(cache.find(source))
    }

    // --- Eviction ---

    @Test
    @DisplayName("the cache is trimmed to its entry limit")
    fun trimsToMaxEntries(@TempDir directory: File) {
        val cacheDir = File(directory, "cache")
        val cache = ArtworkCache(cacheDir, maxEntries = 3, maxBytes = 64L * 1024 * 1024)

        repeat(8) { index ->
            val source = sourceFile(directory, "song$index.flac")
            cache.put(source, cover(size = 512, fill = index.toByte()))
            // lastModified has second granularity on some filesystems; the order
            // only has to be deterministic enough to evict something.
            File(directory, "song$index.flac").setLastModified(1_000L * (index + 1))
        }

        val entries = cacheDir.listFiles()?.filter { it.name.startsWith("art_") } ?: emptyList()
        assertTrue(entries.size <= 3, "cache held ${entries.size} entries, expected at most 3")
    }

    @Test
    @DisplayName("a cache hit is recorded, so eviction is by use and not by write order")
    fun findRecordsTheHit(@TempDir directory: File) {
        // trim() evicts oldest-lastModified first, and nothing but this ever updates
        // it. Without it the cache evicts by write order: the cover of an album
        // played constantly is dropped in favour of one that was never looked at.
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)

        val path = cache.put(source, cover())
        val entry = File(path!!)
        entry.setLastModified(1_000L)

        assertEquals(path, cache.find(source))

        assertTrue(
            entry.lastModified() > 1_000L,
            "a cache hit left the entry looking untouched, so eviction cannot see it"
        )
    }

    @Test
    @DisplayName("a cache hit does not invalidate the entry it just touched")
    fun findIsStillIdempotent(@TempDir directory: File) {
        // The key derives from the *source* file's size and mtime, never from the
        // entry's, so touching the entry must not make the next lookup miss.
        val cache = ArtworkCache(File(directory, "cache"))
        val source = sourceFile(directory)
        val path = cache.put(source, cover())

        assertEquals(path, cache.find(source))
        assertEquals(path, cache.find(source))
        assertEquals(path, cache.find(source))
        assertTrue(File(path!!).readBytes().contentEquals(cover()))
    }

    @Test
    @DisplayName("the cache is trimmed to its byte limit")
    fun trimsToMaxBytes(@TempDir directory: File) {
        val cacheDir = File(directory, "cache")
        val cache = ArtworkCache(cacheDir, maxEntries = 512, maxBytes = 4_096L)

        repeat(6) { index ->
            cache.put(sourceFile(directory, "song$index.flac"), cover(size = 2_048))
        }

        val total = cacheDir.listFiles()
            ?.filter { it.name.startsWith("art_") }
            ?.sumOf { it.length() }
            ?: 0L
        assertTrue(total <= 4_096L, "cache held $total bytes, expected at most 4096")
    }
}
