package org.retromobile.world

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Transfers touch-key edges onto c3emu's single owner thread.
 *
 * A short tap can produce down/up between two emulator slices. In that case the
 * release is deferred until one complete CPU slice has observed the pressed key,
 * matching a physical keypad scan instead of dropping the tap between slices.
 */
internal class C3MatrixInputQueue(
    private val sendNativeKey: (matrixIndex: Int, pressed: Boolean) -> Unit
) {
    private data class Edge(val index: Int, val pressed: Boolean)

    private val incoming = ConcurrentLinkedQueue<Edge>()
    private val pressedAtSlice = mutableMapOf<Int, Long>()
    private val deferredReleases = mutableSetOf<Int>()
    private var completedSlices = 0L

    fun enqueue(index: Int, pressed: Boolean) {
        if (index in 0..255) incoming.add(Edge(index, pressed))
    }

    /** Called on the native owner thread immediately before c3_run. */
    fun beginSlice() {
        while (true) {
            val edge = incoming.poll() ?: break
            if (edge.pressed) {
                if (!pressedAtSlice.containsKey(edge.index)) {
                    sendNativeKey(edge.index, true)
                    pressedAtSlice[edge.index] = completedSlices
                }
                // A new pointer has taken ownership before the old release ran.
                deferredReleases.remove(edge.index)
            } else {
                val downAt = pressedAtSlice[edge.index] ?: continue
                if (completedSlices > downAt) {
                    release(edge.index)
                } else {
                    deferredReleases.add(edge.index)
                }
            }
        }

        // Releases queued before a slice are emitted only after that slice has
        // completed, so even a very quick tap is visible to the emulated keypad.
        deferredReleases.toList().forEach { index ->
            val downAt = pressedAtSlice[index]
            if (downAt == null || completedSlices > downAt) release(index)
        }
    }

    /** Called after the native CPU slice returns. */
    fun completeSlice() {
        completedSlices += 1
    }

    /** Release every key before nativeStop and discard stale queued edges. */
    fun clear() {
        incoming.clear()
        pressedAtSlice.keys.toList().forEach { index ->
            sendNativeKey(index, false)
        }
        pressedAtSlice.clear()
        deferredReleases.clear()
    }

    private fun release(index: Int) {
        if (pressedAtSlice.remove(index) != null) {
            sendNativeKey(index, false)
        }
        deferredReleases.remove(index)
    }
}
