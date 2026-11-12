package com.jiye.dropmate.data

import android.content.Context
import java.util.Properties

/**
 * 개발자가 채우는 `assets/api.properties` 에서 API 키와 TMAP 호출 예산을 읽는다.
 * 키는 로그·결과 CSV·화면에 출력하지 않는다. 미설정·빈 키는 null로 반환한다.
 */
class AppKeyStore(ctx: Context) {
    private val props: Properties = Properties().also { p ->
        runCatching { ctx.assets.open("api.properties").use { p.load(it.reader(Charsets.UTF_8)) } }
    }

    private fun key(name: String): String? = props.getProperty(name)?.trim()?.ifBlank { null }

    fun tmapAppKey(): String? = key("tmap.appKey")

    fun busApiKey(): String? = key("bus.apiKey")

    fun subwayApiKey(): String? = key("subway.apiKey")

    /** 카카오 장소 검색용 REST API 키. */
    fun kakaoRestApiKey(): String? = key("kakao.restApiKey")

    fun budget(): Int? = key("tmap.budget")?.toIntOrNull()?.takeIf { it >= 0 }
}
