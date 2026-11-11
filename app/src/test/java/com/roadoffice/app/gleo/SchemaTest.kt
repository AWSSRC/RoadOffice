package com.roadoffice.app.gleo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gleo App Schema 가이드: actionCalls 는 최대 3개, function 이름은 고유, manifest 의 action 과 일치 */
class SchemaTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val calls = JSONObject(File(root, "assets/schema.json").readText()).getJSONArray("actionCalls")
    private val names = (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test fun atMostThreeFunctions() = assertTrue("actionCalls ${names.size}개 — 최대 3개", names.size <= 3)

    @Test fun uniqueNames() = assertEquals(names.size, names.toSet().size)

    @Test fun manifestDeclaresEveryFunction() {
        val manifest = File(root, "AndroidManifest.xml").readText()
        names.forEach { assertTrue("manifest 에 $it 없음", manifest.contains("android:name=\"$it\"")) }
        assertEquals(names.size, Regex("android:name=\"(MARK_|ADD_|GET_|ASK_)[A-Z_]+\"").findAll(manifest).count())
    }
}
