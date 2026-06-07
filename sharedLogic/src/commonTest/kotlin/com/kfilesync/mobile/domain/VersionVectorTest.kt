package com.kfilesync.mobile.domain

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionVectorTest {

    private val deviceA = DeviceId("device-a")
    private val deviceB = DeviceId("device-b")
    private val deviceC = DeviceId("device-c")

    // ---------- increment ----------

    @Test
    fun increment_from_empty_creates_counter_at_one() {
        val vv = VersionVector().increment(deviceA)
        assertEquals(1L, vv.entries[deviceA])
        assertEquals(1, vv.entries.size)
    }

    @Test
    fun increment_existing_counter_advances_by_one() {
        val vv = VersionVector(mapOf(deviceA to 3L)).increment(deviceA)
        assertEquals(4L, vv.entries[deviceA])
    }

    @Test
    fun increment_does_not_affect_other_devices() {
        val vv = VersionVector(mapOf(deviceA to 5L, deviceB to 2L)).increment(deviceA)
        assertEquals(6L, vv.entries[deviceA])
        assertEquals(2L, vv.entries[deviceB])
    }

    // ---------- isAncestorOf ----------

    @Test
    fun empty_is_ancestor_of_everything() {
        assertTrue(VersionVector().isAncestorOf(VersionVector()))
        assertTrue(VersionVector().isAncestorOf(VersionVector(mapOf(deviceA to 5L))))
    }

    @Test
    fun non_empty_is_not_ancestor_of_empty() {
        assertFalse(VersionVector(mapOf(deviceA to 1L)).isAncestorOf(VersionVector()))
    }

    @Test
    fun strictly_less_is_ancestor() {
        val a = VersionVector(mapOf(deviceA to 1L, deviceB to 2L))
        val b = VersionVector(mapOf(deviceA to 2L, deviceB to 3L))
        assertTrue(a.isAncestorOf(b))
        assertFalse(b.isAncestorOf(a))
    }

    @Test
    fun equal_vectors_are_ancestors_of_each_other() {
        val v = VersionVector(mapOf(deviceA to 3L, deviceB to 7L))
        assertTrue(v.isAncestorOf(v))
    }

    // ---------- conflictsWith ----------

    @Test
    fun ancestor_pair_does_not_conflict() {
        val old = VersionVector(mapOf(deviceA to 1L))
        val newer = VersionVector(mapOf(deviceA to 2L, deviceB to 1L))
        assertFalse(old.conflictsWith(newer))
    }

    @Test
    fun concurrent_edits_conflict() {
        val fromA = VersionVector(mapOf(deviceA to 2L, deviceB to 1L))
        val fromB = VersionVector(mapOf(deviceA to 1L, deviceB to 2L))
        assertTrue(fromA.conflictsWith(fromB))
    }

    // ---------- merge ----------

    @Test
    fun merge_takes_per_key_max() {
        val a = VersionVector(mapOf(deviceA to 3L, deviceB to 1L))
        val b = VersionVector(mapOf(deviceA to 1L, deviceB to 5L, deviceC to 2L))
        val merged = a.merge(b)
        assertEquals(3L, merged.entries[deviceA])
        assertEquals(5L, merged.entries[deviceB])
        assertEquals(2L, merged.entries[deviceC])
    }

    @Test
    fun merge_with_empty_returns_self_values() {
        val a = VersionVector(mapOf(deviceA to 7L))
        val merged = a.merge(VersionVector())
        assertEquals(7L, merged.entries[deviceA])
        assertEquals(1, merged.entries.size)
    }

    // ---------- equality ----------

    @Test
    fun data_class_equality() {
        val a = VersionVector(mapOf(deviceA to 1L, deviceB to 2L))
        val b = VersionVector(mapOf(deviceA to 1L, deviceB to 2L))
        assertEquals(a, b)
    }
}
