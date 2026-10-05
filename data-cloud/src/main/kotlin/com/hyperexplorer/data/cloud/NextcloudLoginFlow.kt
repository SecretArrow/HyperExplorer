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

package com.hyperexplorer.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/**
 * Pabrik [OkHttpClient] untuk klien Nextcloud.
 *
 * Timeout connect/read/write 30 detik dipilih agar tetap sensitif pada jaringan lambat
 * tanpa menggantung UI; permintaan login flow berukuran kecil sehingga nilai ini jauh
 * di atas kebutuhan nyata.
 */
object NextcloudHttp {
    /**
     * Membangun [OkHttpClient] bawaan dengan timeout connect/read/write 30 detik.
     * Setiap pemanggilan mengembalikan instance baru agar pemilik instance mengendalikan
     * siklus hidup klien-nya sendiri (tanpa berbagi kumpulan koneksi global).
     */
    fun default(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build()
}

/**
 * Klien Nextcloud Login flow v2 sesuai spesifikasi resmi Nextcloud.
 *
 * Alur resminya:
 * 1. [start] memulai sesi lewat `POST {server}/index.php/login/v2` dan menghasilkan
 *    [LoginFlowStart]; UI membuka [LoginFlowStart.loginUrl] di browser perangkat.
 * 2. Pengguna menyetujui (atau menolak) permintaan login di browser.
 * 3. [poll] / [awaitCredentials] mem-polling endpoint polling dengan token sesi:
 *    HTTP 202 berarti belum disetujui, HTTP 200 mengembalikan [NextcloudCredentials],
 *    dan HTTP 404 berarti sesi kedaluwarsa.
 *
 * Semua fungsi suspend menjalankan I/O jaringan di Dispatchers.IO. CancellationException
 * hasil pembatalan coroutine TIDAK ditelan dan diteruskan ke pemanggil; kegagalan
 * jaringan/protokol dilaporkan sebagai IOException dengan pesan informatif
 * (APA yang gagal + MENGAPA) sehingga mudah ditampilkan atau dilog.
 *
 * @property serverUrl URL dasar server Nextcloud yang sudah dinormalisasi
 *   (trim, prefix "https://" bila tanpa skema, '/' akhir dibuang).
 * @throws IllegalArgumentException saat konstruksi bila [serverUrl] kosong, tanpa host,
 *   atau bukan URI valid (fail-fast, tanpa menunggu permintaan pertama gagal).
 */
class NextcloudLoginFlow(
    serverUrl: String,
    private val http: OkHttpClient = NextcloudHttp.default(),
) {
    /** URL dasar server ternormalisasi; mis. input " cloud.example.com/nc/ " → "https://cloud.example.com/nc". */
    val serverUrl: String = normalizeServerUrl(serverUrl)

    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Memulai sesi Login flow v2.
     *
     * Mengirim `POST {serverUrl}/index.php/login/v2` dengan body form
     * `app=Hyper%20Explorer` (x-www-form-urlencoded) dan header `Accept: application/json`.
     *
     * @return [LoginFlowStart] berisi loginUrl untuk browser, pollEndpoint, dan pollToken.
     * @throws IOException bila status respons bukan 200, body kosong, JSON rusak,
     *   field wajib hilang/kosong, atau terjadi kegagalan jaringan.
     * @throws CancellationException diteruskan (tidak ditelan).
     */
    suspend fun start(): LoginFlowStart =
        withContext(Dispatchers.IO) {
            val request =
                buildRequest("$serverUrl$LOGIN_FLOW_PATH") {
                    post(FormBody.Builder().add("app", APP_NAME).build())
                }
            execute(request, OP_START).use { response ->
                if (response.code != HTTP_OK) {
                    throw IOException("Nextcloud login flow start failed: HTTP ${response.code}")
                }
                parseStartResponse(readBody(response, OP_START))
            }
        }

    /**
     * Polling satu kali status sesi lewat `POST [LoginFlowStart.pollEndpoint]`
     * dengan body form `token=<pollToken>` dan header `Accept: application/json`.
     *
     * @param start hasil [start] yang berisi endpoint polling dan token sesi.
     * @return [NextcloudCredentials] bila pengguna sudah menyetujui (HTTP 200),
     *   atau `null` eksplisit bila pengguna belum menyetujui (HTTP 202).
     * @throws IOException bila sesi kedaluwarsa (HTTP 404), status tak terduga,
     *   body kosong, JSON rusak, atau field kredensial hilang/kosong.
     * @throws IllegalArgumentException bila [LoginFlowStart.pollToken] kosong.
     */
    suspend fun poll(start: LoginFlowStart): NextcloudCredentials? =
        withContext(Dispatchers.IO) {
            if (start.pollToken.isBlank()) {
                throw IllegalArgumentException("Nextcloud login flow: pollToken is blank; call start() first")
            }
            val request =
                buildRequest(start.pollEndpoint) {
                    post(FormBody.Builder().add("token", start.pollToken).build())
                }
            execute(request, OP_POLL).use { response ->
                when (response.code) {
                    HTTP_OK -> parsePollResponse(readBody(response, OP_POLL))
                    HTTP_ACCEPTED -> null
                    HTTP_NOT_FOUND -> throw IOException("Nextcloud login flow expired (HTTP 404)")
                    else -> throw IOException("Nextcloud login flow poll failed: HTTP ${response.code}")
                }
            }
        }

    /**
     * Polling berulang sampai kredensial tersedia, timeout habis, atau coroutine dibatalkan.
     * Poll pertama langsung dikirim; jeda [intervalMs] hanya berlaku di antara dua polling.
     *
     * @param timeoutMs batas waktu total polling (ms), wajib > 0.
     * @param intervalMs jeda antar polling (ms), wajib >= [MIN_INTERVAL_MS] agar server
     *   tidak diflood permintaan oleh UI yang terburu-buru.
     * @return [NextcloudCredentials] bila sukses; `null` eksplisit bila timeout habis
     *   tanpa persetujuan dari pengguna.
     * @throws IllegalArgumentException bila [timeoutMs] <= 0 atau [intervalMs] < [MIN_INTERVAL_MS].
     * @throws CancellationException diteruskan (ensureActive + delay memeriksa pembatalan).
     */
    suspend fun awaitCredentials(
        start: LoginFlowStart,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): NextcloudCredentials? {
        if (timeoutMs <= 0L) {
            throw IllegalArgumentException("Nextcloud login flow: timeoutMs must be > 0, got $timeoutMs")
        }
        if (intervalMs < MIN_INTERVAL_MS) {
            throw IllegalArgumentException(
                "Nextcloud login flow: intervalMs must be >= $MIN_INTERVAL_MS ms (server flood guard), got $intervalMs",
            )
        }
        return withTimeoutOrNull(timeoutMs) {
            var credentials: NextcloudCredentials? = null
            while (credentials == null) {
                ensureActive()
                credentials = poll(start)
                if (credentials == null) delay(intervalMs)
            }
            credentials
        }
    }

    /** Membangun request dengan header Accept: application/json; URL invalid → IAE informatif. */
    private fun buildRequest(
        url: String,
        configure: Request.Builder.() -> Unit,
    ): Request =
        try {
            Request
                .Builder()
                .url(url)
                .header("Accept", JSON_MEDIA_TYPE)
                .apply(configure)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Nextcloud login flow: invalid request URL '$url': ${e.message}", e)
        }

    /** Eksekusi sinkron (pemanggil wajib berada di Dispatchers.IO); IOException jaringan dibungkus konteks. */
    private fun execute(
        request: Request,
        operation: String,
    ): Response =
        try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("Nextcloud login flow $operation failed: network error: ${e.message}", e)
        }

    /** Membaca body respons; gagal baca atau body null/kosong → IOException informatif. */
    private fun readBody(
        response: Response,
        operation: String,
    ): String {
        val body =
            try {
                response.body?.string()
            } catch (e: IOException) {
                throw IOException("Nextcloud login flow $operation failed: could not read response body: ${e.message}", e)
            }
        if (body.isNullOrBlank()) {
            throw IOException("Nextcloud login flow $operation failed: empty response body (HTTP ${response.code})")
        }
        return body
    }

    /** Parse respons start; JSON rusak / field hilang-kosong → IOException informatif. */
    private fun parseStartResponse(body: String): LoginFlowStart {
        val dto =
            try {
                json.decodeFromString(StartResponseDto.serializer(), body)
            } catch (e: SerializationException) {
                throw IOException("Nextcloud login flow start failed: invalid JSON response: ${e.message}", e)
            }
        if (dto.login.isBlank()) {
            throw IOException("Nextcloud login flow start failed: response field 'login' is missing or blank")
        }
        if (dto.poll.token.isBlank()) {
            throw IOException("Nextcloud login flow start failed: response field 'poll.token' is missing or blank")
        }
        if (dto.poll.endpoint.isBlank()) {
            throw IOException("Nextcloud login flow start failed: response field 'poll.endpoint' is missing or blank")
        }
        return LoginFlowStart(loginUrl = dto.login, pollEndpoint = dto.poll.endpoint, pollToken = dto.poll.token)
    }

    /** Parse respons polling; JSON rusak / kredensial hilang-kosong → IOException informatif. */
    private fun parsePollResponse(body: String): NextcloudCredentials {
        val dto =
            try {
                json.decodeFromString(PollResponseDto.serializer(), body)
            } catch (e: SerializationException) {
                throw IOException("Nextcloud login flow poll failed: invalid JSON response: ${e.message}", e)
            }
        if (dto.server.isBlank()) {
            throw IOException("Nextcloud login flow poll failed: response field 'server' is missing or blank")
        }
        if (dto.loginName.isBlank()) {
            throw IOException("Nextcloud login flow poll failed: response field 'loginName' is missing or blank")
        }
        if (dto.appPassword.isBlank()) {
            throw IOException("Nextcloud login flow poll failed: response field 'appPassword' is missing or blank")
        }
        return NextcloudCredentials(server = dto.server, loginName = dto.loginName, appPassword = dto.appPassword)
    }

    /** DTO respons start sesuai spesifikasi: {"poll":{"token","endpoint"},"login"}. */
    @Serializable
    private data class StartResponseDto(
        val poll: PollDto,
        val login: String,
    )

    /** DTO objek `poll` di dalam respons start. */
    @Serializable
    private data class PollDto(
        val token: String,
        val endpoint: String,
    )

    /** DTO respons polling sesuai spesifikasi: {"server","loginName","appPassword"}. */
    @Serializable
    private data class PollResponseDto(
        val server: String,
        val loginName: String,
        val appPassword: String,
    )

    companion object {
        /** Batas waktu default [awaitCredentials]: 5 menit (pengguna butuh waktu membuka browser). */
        const val DEFAULT_TIMEOUT_MS = 300_000L

        /** Jeda default antar polling: 2 detik (kecepatan respons yang wajar bagi server). */
        const val DEFAULT_INTERVAL_MS = 2_000L

        private const val MIN_INTERVAL_MS = 250L
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_NOT_FOUND = 404
        private const val LOGIN_FLOW_PATH = "/index.php/login/v2"
        private const val APP_NAME = "Hyper Explorer"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val OP_START = "start"
        private const val OP_POLL = "poll"

        /**
         * Normalisasi URL server: trim; tanpa skema → "https://"; buang '/' akhir;
         * wajib URI valid dengan host tidak kosong, selain itu IllegalArgumentException.
         */
        private fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Nextcloud login flow: server URL is blank")
            }
            val withScheme =
                if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
            val normalized = withScheme.trimEnd('/')
            val uri =
                try {
                    URI(normalized)
                } catch (e: URISyntaxException) {
                    throw IllegalArgumentException("Nextcloud login flow: server URL '$raw' is not a valid URI: ${e.reason}", e)
                }
            if (uri.host.isNullOrBlank()) {
                throw IllegalArgumentException("Nextcloud login flow: server URL '$raw' has no host")
            }
            return normalized
        }
    }
}
