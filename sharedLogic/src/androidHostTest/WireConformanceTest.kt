package com.kfilesync.mobile.conformance

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.IndexResponseDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareInviteDto
import com.kfilesync.mobile.application.dto.ShareLeaveDto
import com.kfilesync.mobile.application.dto.TransferAcceptDto
import com.kfilesync.mobile.application.dto.TransferCancelDto
import com.kfilesync.mobile.application.dto.TransferChunkAckDto
import com.kfilesync.mobile.application.dto.TransferRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.fail

@Serializable
private data class WireFixture(
    val name: String,
    val cases: List<WireCase>
)

@Serializable
private data class WireCase(
    val name: String,
    val dto: String,
    val json: JsonElement
)

/**
 * Strict on purpose (default `ignoreUnknownKeys = false`, and
 * `encodeDefaults = true` so a field that happens to equal its Kotlin
 * default still round-trips back onto the wire) - this test's whole point
 * is to catch exactly the field-name/shape drift that a lenient decoder
 * would silently paper over.
 */
private val wireJson = Json { encodeDefaults = true }

private inline fun <reified T> roundTrip(json: JsonElement): JsonElement =
    wireJson.encodeToJsonElement(wireJson.decodeFromJsonElement<T>(json))

/**
 * Parse `case.json` as the hand-written `ApiModels.kt` DTO the fixture
 * names, re-encode it, and return the result for comparison against the
 * original - same schema-conformance check as `run_wire` in
 * `rust-runner/src/main.rs`, just against Kotlin's independently
 * hand-written DTOs instead of Rust's serde structs (there is no shared
 * UniFFI-generated DTO in play here on the Kotlin production path; see
 * ADR-0015 for why the FFI-exported DTO records exist but
 * `HttpServer.kt` / `ApiModels.kt` don't use them).
 */
private fun decodeAndReencode(dto: String, json: JsonElement): JsonElement = when (dto) {
    "DeviceInfoDto" -> roundTrip<DeviceInfoDto>(json)
    "PairRequestDto" -> roundTrip<PairRequestDto>(json)
    "PairConfirmDto" -> roundTrip<PairConfirmDto>(json)
    "PairRevokeDto" -> roundTrip<PairRevokeDto>(json)
    "PairResultDto" -> roundTrip<PairResultDto>(json)
    "ShareInviteDto" -> roundTrip<ShareInviteDto>(json)
    "ShareAuthorizeDto" -> roundTrip<ShareAuthorizeDto>(json)
    "ShareLeaveDto" -> roundTrip<ShareLeaveDto>(json)
    "IndexResponseDto" -> roundTrip<IndexResponseDto>(json)
    "TransferRequestDto" -> roundTrip<TransferRequestDto>(json)
    "TransferAcceptDto" -> roundTrip<TransferAcceptDto>(json)
    "TransferChunkAckDto" -> roundTrip<TransferChunkAckDto>(json)
    "TransferCancelDto" -> roundTrip<TransferCancelDto>(json)
    else -> error("unknown dto type '$dto'")
}

/**
 * Conformance runner (1.3): mirrors `run_wire` in
 * `rust-runner/src/main.rs`. Fails slow - every mismatching case across
 * all 5 wire fixture files is collected into one report instead of
 * stopping at the first, since the point of this test is to enumerate
 * every wire-format drift between `ApiModels.kt` and the fixtures in one
 * pass.
 */
class WireConformanceTest {

    @Test
    fun round_trip_matches_rust() {
        val failures = mutableListOf<String>()

        for (fixturePath in listOf(
            "wire/discovery.json",
            "wire/share.json",
            "wire/sync_index.json",
            "wire/transfer.json",
            "wire/pairing.json"
        )) {
            val fx: WireFixture = fixture(fixturePath)
            for (case in fx.cases) {
                val label = "$fixturePath -> ${case.name} (${case.dto})"
                try {
                    val reencoded = decodeAndReencode(case.dto, case.json)
                    if (reencoded != case.json) {
                        failures += "$label: re-encoded value differs\n original =${case.json}\n reencoded=$reencoded"
                    }
                } catch (e: Exception) {
                    failures += "$label: ${e::class.simpleName}:${e.message}"
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} wire fixture case(s) failed:\n\n" + failures.joinToString("\n\n"))
        }
    }
}