package com.jiye.dropmate.data

import com.jiye.dropmate.engine.Entrance
import com.jiye.dropmate.engine.LatLng

/**
 * 주소기반산업지원서비스(business.juso.go.kr) '내비게이션용DB' 출입구 자리. 2026-11-13 결정: 입구 찾기 1순위.
 * 자료는 신청해서 받을 예정이라 아직 없다 → 지금은 항상 빈 결과(다음 순위인 카카오 검색으로 넘어감).
 *
 * 공개 페이지에서 확인한 것만 적는다(파일 형식은 받은 뒤 맞춘다, 지어내지 않음):
 *  - 건물 단위 도로명주소 + 좌표(건물중심점, 주출입구, 보조출입구)
 *  - '도로명주소 출입구 정보' 스키마: 출입구구분 ENTRC_SE(RM: 주출입구), 출입구유형 ENTRC_TY(01 공용, 02 차량용), 좌표 ENTRC_POINT_X/Y
 *  - 집합건물(아파트)은 주소 기준 주(보조)출입구만 제공, 동 단위 없음
 * 쓰는 방법(확정 전): 차량용(02) 출입구는 운전자·동승자 모두, 공용(01)은 동승자 하차 후보로만.
 */
class JusoEntranceStore {
    /** 자료가 들어오기 전까지 빈 목록 */
    fun forPlace(name: String, pos: LatLng, forDriver: Boolean): List<Entrance> = emptyList()
}
