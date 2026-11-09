package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Test

class C3MatrixInputQueueTest {
    @Test
    fun quickTapRemainsPressedForOneCompleteCpuSlice() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val queue = C3MatrixInputQueue { index, pressed -> edges += index to pressed }

        queue.enqueue(29, true)
        queue.enqueue(29, false)
        queue.beginSlice()

        assertEquals(listOf(29 to true), edges)
        queue.completeSlice()
        queue.beginSlice()

        assertEquals(listOf(29 to true, 29 to false), edges)
    }

    @Test
    fun releaseAfterACompletedSliceIsImmediateAtNextBoundary() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val queue = C3MatrixInputQueue { index, pressed -> edges += index to pressed }

        queue.enqueue(56, true)
        queue.beginSlice()
        queue.completeSlice()
        queue.enqueue(56, false)
        queue.beginSlice()

        assertEquals(listOf(56 to true, 56 to false), edges)
    }

    @Test
    fun aNewPressCancelsAReleaseThatWasDeferredForTheFirstSlice() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val queue = C3MatrixInputQueue { index, pressed -> edges += index to pressed }

        queue.enqueue(43, true)
        queue.enqueue(43, false)
        queue.enqueue(43, true)
        queue.beginSlice()
        queue.completeSlice()
        queue.beginSlice()

        assertEquals(listOf(43 to true), edges)
        queue.completeSlice()
        queue.enqueue(43, false)
        queue.beginSlice()

        assertEquals(listOf(43 to true, 43 to false), edges)
    }

    @Test
    fun clearReleasesAllCurrentlyPressedKeysAndDropsQueuedEvents() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val queue = C3MatrixInputQueue { index, pressed -> edges += index to pressed }

        queue.enqueue(38, true)
        queue.beginSlice()
        queue.enqueue(52, true)
        queue.clear()

        assertEquals(listOf(38 to true, 38 to false), edges)
    }
}
