package com.voyadio.automotive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int) : IOException("Voyadio API status $status")

class VoyadioApi(base: String, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val base = base.trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    }
    suspend fun call(path: String, token: String? = null, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/") && !path.contains(".."))
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code)
            val raw = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(raw)
            if (json.has("ok") && !json.optBoolean("ok")) throw IOException("Rejected response")
            json
        }
    }
}
