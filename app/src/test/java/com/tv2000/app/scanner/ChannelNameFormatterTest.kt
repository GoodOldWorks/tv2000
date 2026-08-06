package com.tv2000.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNameFormatterTest {
    @Test
    fun `keeps a part number but removes the release suffix`() {
        assertEquals(
            "还珠格格 II",
            displayChannelName("还珠格格.II.1999.WEB-DL.1080p.H265.AAC-HDCTV"),
        )
    }

    @Test
    fun `prefers a leading Chinese title over its English alias`() {
        assertEquals(
            "武林外传",
            displayChannelName(
                "武林外传.My.Own.Swordsman.S01.2006.1080p.WEB-DL.H264.AAC-HHWEB",
            ),
        )
        assertEquals("亮剑", displayChannelName("亮剑.Drawing.Sword.S01.1080p.WEB-DL"))
    }

    @Test
    fun `removes Chinese episode language and subtitle metadata`() {
        assertEquals(
            "三国演义",
            displayChannelName("三国演义.1994.全84集.国语.简体中字"),
        )
        assertEquals(
            "倚天屠龙记",
            displayChannelName("倚天屠龙记.2003.简繁中字"),
        )
    }

    @Test
    fun `extracts the title block from a bracketed release name`() {
        assertEquals(
            "Spy x Family Code White",
            displayChannelName("[2023 Movie][Spy x Family Code White][BDRIP][1080P+SP]"),
        )
        assertEquals(
            "No Game No Life Zero",
            displayChannelName("[VCB-Studio] No Game No Life Zero [Ma10p_1080p]"),
        )
    }

    @Test
    fun `keeps punctuation in an English title while removing release metadata`() {
        assertEquals(
            "Gone.Girl",
            displayChannelName(
                "Gone.Girl.2014.2160p.WEB-DL.x265.10bit.SDR.DTS-HD.MA.7.1-SWTYBLZ",
            ),
        )
        assertEquals(
            "Blade.Runner.2049",
            displayChannelName("Blade.Runner.2049.2017.1080p.BluRay"),
        )
    }

    @Test
    fun `leaves ordinary and ambiguous names unchanged`() {
        assertEquals("西游记", displayChannelName("西游记"))
        assertEquals("A.B.C", displayChannelName("A.B.C"))
        assertEquals("1984", displayChannelName("1984"))
        assertEquals("[1984]", displayChannelName("[1984]"))
        assertEquals("国语", displayChannelName("国语"))
    }
}
