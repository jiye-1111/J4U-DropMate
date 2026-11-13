package com.jiye.dropmate.data

import android.content.Context
import java.util.Properties

/**
 * 개발자가 채우는 `assets/api.properties` 에서 API 키와 TMAP 호출 예산을 읽는다.
 * 키는 로그·결과 CSV·화면에 출력하지 않는다. 미설정·빈 키는 null로 반환한다.
 */
class AppKeyStore(ctx: Context) {
    /** 사용자가 설정 화면에서 직접 넣은 TMAP 키. 이 기기 안(SharedPreferences)에만 둔다. */
    private val userSp = ctx.getSharedPreferences("dropmate_user_keys", Context.MODE_PRIVATE)
    private val props: Properties = Properties().also { p ->
        runCatching { ctx.assets.open("api.properties").use { p.load(it.reader(Charsets.UTF_8)) } }
    }

    private fun key(name: String): String? = props.getProperty(name)?.trim()?.ifBlank { null }

    /** 직접 입력한 키가 있으면 그 키, 없으면 api.properties 기본 키. */
    fun tmapAppKey(): String? = userTmapKey() ?: key("tmap.appKey")

    private fun userTmapKey(): String? = userSp.getString(USER_TMAP, null)?.trim()?.ifBlank { null }

    fun hasUserTmapKey(): Boolean = userTmapKey() != null

    /** 화면 표시용: 직접 입력한 키의 앞 4자리만. */
    fun userTmapKeyPrefix(): String? = userTmapKey()?.take(4)

    fun setUserTmapKey(v: String) { userSp.edit().putString(USER_TMAP, v.trim()).apply() }

    /** '지우기': 기본 키로 돌아간다. */
    fun clearUserTmapKey() { userSp.edit().remove(USER_TMAP).apply() }

    fun busApiKey(): String? = key("bus.apiKey")

    fun subwayApiKey(): String? = key("subway.apiKey")

    /** 카카오 장소 검색용 REST API 키. */
    fun kakaoRestApiKey(): String? = key("kakao.restApiKey")

    /** 로컬 예산은 기본 키(공용 무료 키)에만 적용한다. 본인 키를 쓰면 그 키의 한도는 TMAP이 관리한다. */
    fun budget(): Int? = if (hasUserTmapKey()) null else key("tmap.budget")?.toIntOrNull()?.takeIf { it >= 0 }

    private companion object { const val USER_TMAP = "tmap.userKey" }
}
