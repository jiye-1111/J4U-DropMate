package com.jiye.dropmate.data

import android.content.Context
import com.jiye.dropmate.engine.Entrance
import com.jiye.dropmate.engine.EntranceKind
import com.jiye.dropmate.engine.Geo
import com.jiye.dropmate.engine.LatLng

/**
 * 공공·공개 자료로 만든 입구 목록(assets/entrances/entrances.tsv). 2026-11-12 추가.
 * 출처: OSM(차량 진입로가 공공도로와 만나는 점), 서울시 차량 출입구 정보 OA-21700(2022-12 조사, 이후 미갱신).
 * TMAP 응답은 약관상 24시간 넘게 보관할 수 없으므로 이 파일에 넣지 않는다(요청 때마다 조회).
 */
class EntranceStore(ctx: Context) {
    private data class Row(val group: String, val keys: List<String>, val kind: EntranceKind, val label: String, val pos: LatLng, val source: String, val checked: String)

    private val rows: List<Row> = runCatching {
        ctx.assets.open("entrances/entrances.tsv").bufferedReader(Charsets.UTF_8).useLines { ls ->
            ls.drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 8) return@mapNotNull null
                val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
                val lon = f[5].toDoubleOrNull() ?: return@mapNotNull null
                Row(f[0], f[1].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                    if (f[2].trim().uppercase() == "WALK") EntranceKind.WALK else EntranceKind.CAR, f[3].trim(), LatLng(lat, lon), f[6].trim(), f[7].trim())
            }.toList()
        }
    }.getOrDefault(emptyList())

    val size: Int get() = rows.size

    private fun norm(s: String) = s.replace(" ", "").lowercase()

    /**
     * 장소 이름에 단지 검색어가 있고 입구가 800m 안이면 그 단지 입구를 가까운 순 max 개.
     * 이름이 비었거나 맞는 단지가 없으면 빈 목록(원래 좌표 그대로 사용).
     * 2026-11-12 결정: 이름이 맞을 때만 입구 사용. '150m 안 가장 가까운 시설' 대체는 역 근처 남의 건물 입구를 잡아 제거.
     */
    fun forPlace(name: String?, pos: LatLng, max: Int = 3): List<Entrance> {
        val n = name?.let(::norm).orEmpty()
        if (n.isBlank()) return emptyList()
        val picked = rows.filter { r ->
            r.keys.any { k -> norm(k).length >= 3 && n.contains(norm(k)) } && Geo.haversineM(r.pos, pos) <= 800.0
        }
        return picked.sortedBy { Geo.haversineM(it.pos, pos) }.take(max)
            .map { Entrance(it.pos, it.label, it.kind, "${it.source} · 확인 ${it.checked}") }
    }
}
