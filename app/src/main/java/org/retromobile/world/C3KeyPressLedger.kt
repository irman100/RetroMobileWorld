package org.retromobile.world

/**
 * Tracks pointer-to-matrix-key ownership and emits only real matrix edges.
 *
 * A key stays down until the last pointer holding it is released. Moving a
 * pointer between circles releases the old key before pressing the new one.
 */
internal class C3KeyPressLedger(
    private val onKeyEdge: (matrixIndex: Int, pressed: Boolean) -> Unit
) {
    private val pointerKeys = mutableMapOf<Int, Int>()
    private val keyPointerCounts = mutableMapOf<Int, Int>()

    fun move(pointerId: Int, nextKey: Int?) {
        val previousKey = pointerKeys[pointerId]
        if (previousKey == nextKey) return

        if (previousKey != null) {
            pointerKeys.remove(pointerId)
            releaseKey(previousKey)
        }
        if (nextKey != null) {
            pointerKeys[pointerId] = nextKey
            pressKey(nextKey)
        }
    }

    fun release(pointerId: Int) {
        val key = pointerKeys.remove(pointerId) ?: return
        releaseKey(key)
    }

    fun clear() {
        pointerKeys.keys.toList().forEach(::release)
        pointerKeys.clear()
        keyPointerCounts.clear()
    }

    private fun pressKey(key: Int) {
        val current = keyPointerCounts[key] ?: 0
        if (current == 0) onKeyEdge(key, true)
        keyPointerCounts[key] = current + 1
    }

    private fun releaseKey(key: Int) {
        val current = keyPointerCounts[key] ?: return
        if (current <= 1) {
            keyPointerCounts.remove(key)
            onKeyEdge(key, false)
        } else {
            keyPointerCounts[key] = current - 1
        }
    }
}
