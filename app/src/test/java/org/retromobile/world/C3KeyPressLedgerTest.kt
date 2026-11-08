package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Test

class C3KeyPressLedgerTest {
    @Test
    fun tapEmitsOneDownAndOneUpEdge() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val ledger = C3KeyPressLedger { key, down -> events.add(key to down) }

        ledger.move(pointerId = 1, nextKey = 56)
        ledger.release(pointerId = 1)

        assertEquals(listOf(56 to true, 56 to false), events)
    }

    @Test
    fun movingBetweenCirclesReleasesOldKeyBeforePressingNewKey() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val ledger = C3KeyPressLedger { key, down -> events.add(key to down) }

        ledger.move(pointerId = 7, nextKey = 0)
        ledger.move(pointerId = 7, nextKey = 1)
        ledger.move(pointerId = 7, nextKey = null)

        assertEquals(
            listOf(0 to true, 0 to false, 1 to true, 1 to false),
            events
        )
    }

    @Test
    fun sharedKeyRemainsDownUntilEveryPointerReleasesIt() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val ledger = C3KeyPressLedger { key, down -> events.add(key to down) }

        ledger.move(pointerId = 1, nextKey = 60)
        ledger.move(pointerId = 2, nextKey = 60)
        ledger.release(pointerId = 1)

        assertEquals(listOf(60 to true), events)

        ledger.release(pointerId = 2)
        assertEquals(listOf(60 to true, 60 to false), events)
    }

    @Test
    fun cancelReleasesEachHeldMatrixKeyExactlyOnce() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val ledger = C3KeyPressLedger { key, down -> events.add(key to down) }

        ledger.move(pointerId = 1, nextKey = 29)
        ledger.move(pointerId = 2, nextKey = 29)
        ledger.move(pointerId = 3, nextKey = 43)
        ledger.clear()
        ledger.clear()

        assertEquals(
            listOf(29 to true, 43 to true, 29 to false, 43 to false),
            events
        )
    }
}
