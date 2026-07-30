package com.tv2000.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {
    @Test
    fun `plain numbers sort by numeric value`() {
        val actual = listOf("11", "2", "10", "1").sortedWith(NaturalOrderComparator)

        assertEquals(listOf("1", "2", "10", "11"), actual)
    }

    @Test
    fun `season episode names sort naturally`() {
        val actual = listOf(
            "S01E10",
            "S02E01",
            "S01E02",
            "S01E01",
        ).sortedWith(NaturalOrderComparator)

        assertEquals(
            listOf("S01E01", "S01E02", "S01E10", "S02E01"),
            actual,
        )
    }

    @Test
    fun `Chinese episode names sort naturally`() {
        val actual = listOf("第10集", "第2集", "第01集").sortedWith(NaturalOrderComparator)

        assertEquals(listOf("第01集", "第2集", "第10集"), actual)
    }

    @Test
    fun `full width digits are normalized`() {
        val actual = listOf("第１０集", "第２集", "第１集").sortedWith(NaturalOrderComparator)

        assertEquals(listOf("第１集", "第２集", "第１０集"), actual)
    }

    @Test
    fun `equal numeric values prefer fewer leading zeros`() {
        val actual = listOf("001", "01", "1").sortedWith(NaturalOrderComparator)

        assertEquals(listOf("1", "01", "001"), actual)
    }

    @Test
    fun `arbitrarily large numbers remain safe`() {
        val actual = listOf(
            "episode999999999999999999999999",
            "episode10",
            "episode1000000000000000000000000",
        ).sortedWith(NaturalOrderComparator)

        assertEquals(
            listOf(
                "episode10",
                "episode999999999999999999999999",
                "episode1000000000000000000000000",
            ),
            actual,
        )
    }
}
