/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cn.enaium.vlc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VlcTypesTest {

    @Test
    fun `state codes round-trip`() {
        VlcState.entries.forEach { assertEquals(it, VlcState.fromCode(it.code)) }
        assertEquals(VlcState.NothingSpecial, VlcState.fromCode(99))
    }

    @Test
    fun `track and parse codes round-trip`() {
        VlcTrackType.entries.forEach { assertEquals(it, VlcTrackType.fromCode(it.code)) }
        VlcParsedStatus.entries.forEach { assertEquals(it, VlcParsedStatus.fromCode(it.code)) }
    }

    @Test
    fun `browser capabilities are explicit about unsupported features`() {
        val capabilities = VlcCapabilities.browser()
        assertTrue(!capabilities.nativeLibVlc)
        assertTrue(!capabilities.softwareVideoCallbacks)
        assertTrue(!capabilities.snapshot)
        assertTrue(capabilities.protocols.contains("https"))
    }
}
