/*
 * Hyper Explorer - a free and open source file manager for Android.
 * Copyright (C) 2025-2026 Hyper Explorer contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.hyperexplorer.feature.media.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRouterTest {
    @Test
    fun `image extensions route to image viewer`() {
        val uppercaseJpg = ViewerRouter.routeFor("foto.JPG")
        assertTrue(uppercaseJpg is ViewerRoute.Image)
        assertEquals("foto.JPG", (uppercaseJpg as ViewerRoute.Image).path)

        val nestedPng = ViewerRouter.routeFor("a/b/c.png")
        assertTrue(nestedPng is ViewerRoute.Image)
        assertEquals("a/b/c.png", (nestedPng as ViewerRoute.Image).path)
    }

    @Test
    fun `text extensions route to text viewer`() {
        val markdown = ViewerRouter.routeFor("notes.md")
        assertTrue(markdown is ViewerRoute.Text)
        assertEquals("notes.md", (markdown as ViewerRoute.Text).path)

        val kotlinSource = ViewerRouter.routeFor("app.kt")
        assertTrue(kotlinSource is ViewerRoute.Text)
        assertEquals("app.kt", (kotlinSource as ViewerRoute.Text).path)
    }

    @Test
    fun `extensionless name returns null`() {
        assertNull(ViewerRouter.routeFor("README"))
    }

    @Test
    fun `unknown extensions return null`() {
        assertNull(ViewerRouter.routeFor("video.mp4"))
        assertNull(ViewerRouter.routeFor("x.PDF"))
    }
}
