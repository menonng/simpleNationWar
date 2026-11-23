package dev.menonng.snw.bot

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 마크 이름 → UUID(모장 공개 프로필). 가입할 때 플레이어가 적은 이름만 보낸다. */
object MojangLookup {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    fun uuidOf(name: String): String? {
        if (!name.matches(Regex("[A-Za-z0-9_]{3,16}"))) return null
        return runCatching {
            val res = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/$name")).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null
            val id = JsonParser.parseString(res.body()).asJsonObject["id"].asString
            "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
        }.getOrNull()
    }
}
