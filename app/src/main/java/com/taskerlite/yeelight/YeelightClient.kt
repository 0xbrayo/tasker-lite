package com.taskerlite.yeelight

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Short-lived TCP client for the Yeelight LAN control protocol (port 55443).
 * Each command opens a connection, sends one JSON message, reads the result, and closes.
 */
class YeelightClient(
    private val host: String,
    private val port: Int = 55443,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 3_000,
) {
    private val nextId = AtomicInteger(1)

    suspend fun setPower(on: Boolean, durationMs: Int = 500): Result<Unit> =
        send("set_power", listOf(if (on) "on" else "off", "smooth", durationMs.coerceAtLeast(30)))

    suspend fun setBright(percent: Int, durationMs: Int = 500): Result<Unit> =
        send(
            "set_bright",
            listOf(percent.coerceIn(1, 100), "smooth", durationMs.coerceAtLeast(30))
        )

    suspend fun setCt(kelvin: Int, durationMs: Int = 500): Result<Unit> =
        send(
            "set_ct_abx",
            listOf(kelvin.coerceIn(1700, 6500), "smooth", durationMs.coerceAtLeast(30))
        )

    suspend fun setRgb(color: Int, durationMs: Int = 500): Result<Unit> =
        send(
            "set_rgb",
            listOf(color and 0xFFFFFF, "smooth", durationMs.coerceAtLeast(30))
        )

    suspend fun toggle(): Result<Unit> = send("toggle", emptyList())

    /**
     * Offload a multi-step fade to the bulb firmware.
     * @param count how many times to run the flow (1 = once)
     * @param action 0 = stay on last state when finished, 1 = recover previous, 2 = off
     * @param flowExpression comma-separated tuples: duration_ms,mode,value,brightness
     */
    suspend fun startColorFlow(count: Int, action: Int, flowExpression: String): Result<Unit> =
        send("start_cf", listOf(count, action, flowExpression))

    suspend fun stopColorFlow(): Result<Unit> = send("stop_cf", emptyList())

    suspend fun getProp(vararg props: String): Result<Map<String, String>> {
        val keys = props.toList().ifEmpty { listOf("power", "bright", "ct", "rgb", "name") }
        return sendRaw("get_prop", keys).map { resultArray ->
            keys.mapIndexed { index, key ->
                val value = resultArray.getOrNull(index)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                key to value
            }.toMap()
        }
    }

    suspend fun ping(): Result<Map<String, String>> = getProp("power", "bright")

    private suspend fun send(method: String, params: List<Any>): Result<Unit> =
        sendRaw(method, params).map { }

    private suspend fun sendRaw(method: String, params: List<Any>): Result<JsonArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = nextId.getAndIncrement()
                val payload = buildCommand(id, method, params)
                Log.d(TAG, "→ $host:$port $payload")

                Socket().use { socket ->
                    socket.soTimeout = readTimeoutMs
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    writer.write(payload)
                    writer.write("\r\n")
                    writer.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val line = reader.readLine()
                        ?: error("No response from bulb at $host:$port")
                    Log.d(TAG, "← $line")

                    val json = Json.parseToJsonElement(line).jsonObject
                    val error = json["error"]?.jsonObject
                    if (error != null) {
                        val code = error["code"]?.jsonPrimitive?.contentOrNull
                        val message = error["message"]?.jsonPrimitive?.contentOrNull
                        error("Bulb error $code: $message")
                    }
                    when (val result = json["result"]) {
                        is JsonArray -> result
                        is JsonElement -> JsonArray(listOf(result))
                        null -> JsonArray(emptyList())
                    }
                }
            }.onFailure {
                Log.e(TAG, "Command failed: $method @ $host", it)
            }
        }

    companion object {
        private const val TAG = "YeelightClient"

        fun buildCommand(id: Int, method: String, params: List<Any>): String {
            val obj = buildJsonObject {
                put("id", id)
                put("method", method)
                putJsonArray("params") {
                    params.forEach { param ->
                        when (param) {
                            is Int -> add(JsonPrimitive(param))
                            is Long -> add(JsonPrimitive(param))
                            is Boolean -> add(JsonPrimitive(param))
                            is Number -> add(JsonPrimitive(param.toInt()))
                            else -> add(JsonPrimitive(param.toString()))
                        }
                    }
                }
            }
            return obj.toString()
        }
    }
}
