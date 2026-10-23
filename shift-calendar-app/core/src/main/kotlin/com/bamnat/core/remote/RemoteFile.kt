package com.bamnat.core.remote

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.Base64

/**
 * [REMOTE-01] One remote data file (결정 D3): `remote/v1/<kind>.json` at the top of the repository, served by
 * GitHub Pages next to the privacy policy (`https://ywjmoney.github.io/-/remote/v1/`), with `<kind>.json.sig`
 * beside it holding the Ed25519 signature of the file's exact bytes in base64. The same JSON ships inside
 * the app (core resources), so a phone that never goes online has the data the release had.
 *
 * ```
 * {"format": 1, "kind": "presets", "version": 2026102201, "issued": "2026-10-22", "minApp": 33,
 *  "source": "...", "data": {...}}
 * ```
 * [version] only grows (날짜 + 두 자리); a phone takes a downloaded file only when it is newer than what it
 * has. [minApp] is the lowest versionCode that understands [data]; an older app ignores the file.
 */
data class RemoteFile(
    val kind: String,
    val version: Long,
    val issued: LocalDate,
    val minApp: Int,
    val source: String,
    val data: JsonObj,
)

object RemoteFiles {
    const val FORMAT = 1L
    const val PRESETS = "presets"
    const val RATES = "rates"
    const val WAGES = "wages"
    const val TAXTABLE = "taxtable"
    /** The files the app reads; each ships inside the app and is published at [path]. */
    val KINDS = listOf(PRESETS, RATES, WAGES, TAXTABLE)

    /** Where the files live on the site, below the app's `bamnat.remoteBaseUrl`. */
    fun path(kind: String) = "remote/v1/$kind.json"

    /** Reads [bytes] strictly (UTF-8, JSON, envelope); any fault is a [RemoteFileException]. */
    fun parse(bytes: ByteArray): RemoteFile {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw RemoteFileException("not UTF-8")
        }
        @Suppress("UNCHECKED_CAST")
        val o = JsonObj(Json.parse(text.removePrefix("﻿")) as? Map<String, Any?> ?: throw RemoteFileException("not an object"))
        if (o.long("format") != FORMAT) throw RemoteFileException("format")
        val issued = try { LocalDate.parse(o.str("issued")) } catch (e: java.time.format.DateTimeParseException) { throw RemoteFileException("issued") }
        return RemoteFile(o.str("kind"), o.long("version"), issued, o.int("minApp"), o.strOr("source", ""), o.obj("data"))
    }

    /** True when [signature] (base64 of 64 bytes) is one of [keys]' (base64 of 32 bytes) signature of [bytes]. */
    fun verify(bytes: ByteArray, signature: String, keys: List<String> = RemoteKeys.TRUSTED): Boolean {
        val sig = try { Base64.getDecoder().decode(signature.trim()) } catch (e: IllegalArgumentException) { return false }
        return keys.any { k -> Ed25519.verify(Base64.getDecoder().decode(k), bytes, sig) }
    }

    /**
     * A downloaded file the phone may use: signed by a trusted key, the [kind] asked for, and meant for an
     * app at [appVersion] or older. Anything else is a [RemoteFileException] and the phone keeps its data.
     */
    fun open(bytes: ByteArray, signature: String, kind: String, appVersion: Int, keys: List<String> = RemoteKeys.TRUSTED): RemoteFile {
        if (!verify(bytes, signature, keys)) throw RemoteFileException("signature")
        val f = parse(bytes)
        if (f.kind != kind) throw RemoteFileException("kind ${f.kind}")
        if (f.minApp > appVersion) throw RemoteFileException("needs app ${f.minApp}")
        return f
    }

    /** The copy built into the app (trusted as part of the signed APK); null only if packaging lost it. */
    fun bundledBytes(kind: String): ByteArray? =
        RemoteFiles::class.java.getResourceAsStream("/com/bamnat/core/remote/v1/$kind.json")?.use { it.readBytes() }

    fun bundled(kind: String): RemoteFile? = bundledBytes(kind)?.let { runCatching { parse(it) }.getOrNull() }
}

/**
 * [REMOTE-01] The public keys whose signatures the app trusts, base64 of the raw 32 bytes. The private key
 * is kept only in the project's private `release-key/` folder (결정 D3), never in this repository. A second
 * key can be added here one release before switching to it.
 */
object RemoteKeys {
    val TRUSTED: List<String> = listOf(
        // 2026-10-22 key, private half in the project's release-key/bamnat-remote-ed25519.pem.
        "6e6J4SWfNWAY8142S4+UDOjBJz+4/njmfjxATwAl55I=",
        // [REMOTE-04] 2026-10-23 spare key (키 교체 대비), private half in release-key/bamnat-remote-ed25519-2.pem.
        // Files are still signed with the first key; this one lets a later release switch keys, or recover from a
        // lost one, without stranding phones that never updated.
        "fzoA27EsvTUDa4EA/xtL/z4039vz1TSwmTr48lksmXQ=",
    )
}
