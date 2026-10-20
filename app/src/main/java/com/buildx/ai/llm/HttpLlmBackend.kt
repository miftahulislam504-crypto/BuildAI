package com.buildx.ai.llm

import com.buildx.ai.agent.AgentBackend
import com.buildx.ai.agent.AgentOutcome
import com.buildx.ai.agent.AgentRequest
import com.buildx.ai.agent.AgentRequestJson
import com.buildx.ai.agent.AgentStepParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** POSTs a request to the proxy over HTTPS. Plain java.net, no extra libraries. Serves both the command fallback and the screen agent. */
class HttpLlmBackend(
    private val url: String,
    private val token: String,
    private val connectMs: Int = 5_000,
    private val readMs: Int = 12_000
) : LlmBackend, AgentBackend {

    override fun plan(req: LlmRequest): LlmOutcome =
        exchange(
            LlmRequestJson.build(req),
            ok = { code, body -> LlmPlanParser.fromHttp(code, body, req.text) },
            failed = { why, detail -> LlmOutcome.Failed(why, detail) }
        )

    override fun next(req: AgentRequest): AgentOutcome =
        exchange(
            AgentRequestJson.build(req),
            ok = { code, body -> AgentStepParser.fromHttp(code, body) },
            failed = { why, detail -> AgentOutcome.Failed(why, detail) }
        )

    private fun <T> exchange(json: String, ok: (Int, String) -> T, failed: (Failure, String) -> T): T {
        var conn: HttpURLConnection? = null
        return try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectMs
                readTimeout = readMs
                doOutput = true
                instanceFollowRedirects = false // a redirect could carry the token somewhere else
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            ok(code, readCapped(stream))
        } catch (e: SocketTimeoutException) {
            failed(Failure.TIMEOUT, "timeout")
        } catch (e: IOException) {
            failed(Failure.NETWORK, e.javaClass.simpleName)
        } catch (e: Exception) {
            failed(Failure.BAD_RESPONSE, e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** at most 64 KB: a runaway server must not fill the phone's memory */
    private fun readCapped(s: InputStream?): String {
        if (s == null) return ""
        s.use {
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < MAX_BODY) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    private companion object {
        const val MAX_BODY = 64 * 1024
    }
}
