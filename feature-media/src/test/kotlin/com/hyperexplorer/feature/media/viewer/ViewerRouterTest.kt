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
        // Catatan Task 8-c: asersi lama "video.mp4" -> null dipindah ke test video karena
        // sejak varian Video/Audio ditambahkan, "video.mp4" dipetakan ke ViewerRoute.Video.
        assertNull(ViewerRouter.routeFor("x.PDF"))
    }

    @Test
    fun `video extensions route to video player`() {
        val mp4 = ViewerRouter.routeFor("video.mp4")
        assertTrue(mp4 is ViewerRoute.Video)
        assertEquals("video.mp4", (mp4 as ViewerRoute.Video).path)

        val uppercaseMp4 = ViewerRouter.routeFor("MOVIE.MP4")
        assertTrue(uppercaseMp4 is ViewerRoute.Video)

        val webm = ViewerRouter.routeFor("a.webm")
        assertTrue(webm is ViewerRoute.Video)
    }

    @Test
    fun `dot inside folder name does not break video routing`() {
        val nested = ViewerRouter.routeFor("/sdcard/Download/a.b/film.mkv")
        assertTrue(nested is ViewerRoute.Video)
        assertEquals("/sdcard/Download/a.b/film.mkv", (nested as ViewerRoute.Video).path)
    }

    @Test
    fun `audio extensions route to audio player`() {
        val mp3 = ViewerRouter.routeFor("lagu.mp3")
        assertTrue(mp3 is ViewerRoute.Audio)
        assertEquals("lagu.mp3", (mp3 as ViewerRoute.Audio).path)

        val uppercaseFlac = ViewerRouter.routeFor("audio.FLAC")
        assertTrue(uppercaseFlac is ViewerRoute.Audio)

        val m4a = ViewerRouter.routeFor("x.m4a")
        assertTrue(m4a is ViewerRoute.Audio)
    }

    @Test
    fun `trailing dot returns null`() {
        assertNull(ViewerRouter.routeFor("file."))
    }

    @Test
    fun `dotfile with unknown extension returns null`() {
        assertNull(ViewerRouter.routeFor(".profile"))
    }

    @Test
    fun `blank paths return null`() {
        assertNull(ViewerRouter.routeFor(""))
        assertNull(ViewerRouter.routeFor("   "))
    }

    @Test
    fun `unknown non media extension still returns null`() {
        assertNull(ViewerRouter.routeFor("dokumen.xyz"))
    }

    @Test
    fun `text routing regression unchanged`() {
        val text = ViewerRouter.routeFor("catatan.txt")
        assertTrue(text is ViewerRoute.Text)
        assertEquals("catatan.txt", (text as ViewerRoute.Text).path)
    }

    @Test
    fun `image routing regression unchanged`() {
        val image = ViewerRouter.routeFor("foto.jpg")
        assertTrue(image is ViewerRoute.Image)
        assertEquals("foto.jpg", (image as ViewerRoute.Image).path)
    }
}
