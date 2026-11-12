package com.jiye.dropmate.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Studio / Gradle 에서 합성 검증 전체를 실행: ./gradlew :engine:test */
class EngineJUnitTest {
    @Test fun syntheticChecks() {
        val (_, fails) = EngineChecks.runAll()
        assertTrue("실패: \n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
