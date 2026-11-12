package com.jiye.dropmate.data

import com.jiye.dropmate.engine.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** category = "그룹코드|분류명"(예: "SW8|교통,수송 > 지하철,전철 > 수도권2호선"). 역·아파트 판별에 쓴다. */
data class KakaoPlace(val id: String, val name: String, val position: LatLng, val address: String?, val category: String? = null)
data class KakaoPlacePage(val places: List<KakaoPlace>, val hasMore: Boolean, val totalCount: Int)
class PlaceSearchException(val userMessage: String) : IOException(userMessage)

/** 카카오 키워드 검색과 결과가 없을 때의 접두어 보조 검색. 키·응답 원문은 저장하지 않는다. */
class KakaoPlaceSearch {
    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun search(key: String, query: String, page: Int): KakaoPlacePage {
        val direct = searchPage(key, query, page)
        if (page != 1 || direct.totalCount != 0 || query.trim().length < 3) return direct

        // 미완성 마지막 음절 때문에 결과가 없는 경우 한 글자만 줄여 검색한다.
        // 보조 검색은 최대 3페이지(45개)로 제한하고, 원래 입력과 무관한 장소는 제외한다.
        val prefix = normalize(query)
        val broaderQuery = query.trim().dropLast(1).trimEnd()
        if (normalize(broaderQuery).length < 2) return direct
        val candidates = mutableListOf<KakaoPlace>()
        for (fallbackPage in 1..3) {
            val response = searchPage(key, broaderQuery, fallbackPage)
            candidates += response.places.filter { normalize(it.name).startsWith(prefix) }
            if (!response.hasMore) break
        }
        val matches = candidates.distinctBy { it.id }
        // 원래 검색어와 완전히 일치하는 장소 우선, 나머지는 카카오 정확도 순서 유지.
        val ranked = matches.sortedBy { if (normalize(it.name) == prefix) 0 else 1 }
        return KakaoPlacePage(ranked, false, ranked.size)
    }

    private fun normalize(value: String): String =
        value.filterNot { it.isWhitespace() }.lowercase(java.util.Locale.ROOT)

    private suspend fun searchPage(key: String, query: String, page: Int): KakaoPlacePage {
        require(page in 1..3)
        val url = HttpUrl.Builder().scheme("https").host("dapi.kakao.com")
            .addPathSegments("v2/local/search/keyword.json")
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("size", "15")
            .addQueryParameter("sort", "accuracy")
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "KakaoAK $key").build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(
                        PlaceSearchException("장소 검색에 연결할 수 없습니다. 네트워크를 확인하고 다시 검색하세요.")
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { r ->
                            if (!r.isSuccessful) {
                                val message = when (r.code) {
                                    401, 403 -> "카카오 검색 인증 또는 권한을 확인하세요."
                                    429 -> "카카오 검색 요청 한도를 초과했습니다. 잠시 후 다시 검색하세요."
                                    else -> "카카오 장소 검색에 실패했습니다. 다시 검색하세요."
                                }
                                throw PlaceSearchException(message)
                            }
                            val json = JSONObject(r.body?.string() ?: throw IOException())
                            val documents = json.getJSONArray("documents")
                            val places = (0 until documents.length()).map { i ->
                                val item = documents.getJSONObject(i)
                                val road = item.optString("road_address_name").ifBlank { null }
                                val address = item.optString("address_name").ifBlank { null }
                                KakaoPlace(
                                    item.getString("id"), item.getString("place_name"),
                                    LatLng(item.getString("y").toDouble(), item.getString("x").toDouble()),
                                    if (road != null && address != null && road != address) "$road (지번 $address)"
                                    else road ?: address,
                                    "${item.optString("category_group_code")}|${item.optString("category_name")}"
                                )
                            }
                            val meta = json.getJSONObject("meta")
                            KakaoPlacePage(
                                places,
                                !meta.getBoolean("is_end") && page < 3 && page * 15 < meta.getInt("pageable_count"),
                                meta.getInt("total_count")
                            )
                        }
                    }.recoverCatching { e ->
                        throw (e as? PlaceSearchException
                            ?: PlaceSearchException("카카오 검색 응답을 처리하지 못했습니다. 다시 검색하세요."))
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }
}
