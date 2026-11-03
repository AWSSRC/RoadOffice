package com.roadoffice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * 로드오피스 색 체계 — 낮에는 Pleos 시스템 UI 와 같은 라이트 톤, 밤에는 눈부심을 줄이는 현대 블루 다크.
 * 차량 주·야간(시스템 야간 모드)을 따라 자동으로 바뀐다.
 */
data class RoColors(
    val dark: Boolean,
    val bg: Color,          // 화면 바탕
    val surface: Color,     // 카드
    val field: Color,       // 카드 안 칩·입력 바탕
    val ink: Color,         // 본문 글자
    val sub: Color,         // 보조 글자
    val accent: Color,      // 주 버튼·선택 상태 바탕
    val onAccent: Color,    // 주 버튼 위 글자
    val accentText: Color,  // 강조 글자 (제목·수치)
    val warn: Color,        // 주의 배지 바탕
    val onWarn: Color,
    val warnText: Color,    // 주의 글자 (남은 할 일 등)
    val banner: Color,      // "미팅 어땠어요?" 같은 알림 카드
    val line: Color,
    val rail: Color,        // 왼쪽 메뉴 바탕
)

private val HyundaiNavy = Color(0xFF002C5F)
private val ActiveBlue = Color(0xFF00AAD2)

val LightRo = RoColors(
    dark = false,
    bg = Color(0xFFF2F4F7), surface = Color.White, field = Color(0xFFE9EEF4),
    ink = Color(0xFF0E1A2B), sub = Color(0xFF5A6676),
    accent = HyundaiNavy, onAccent = Color.White, accentText = Color(0xFF0067A0),
    warn = Color(0xFFFFB81C), onWarn = Color(0xFF2A1C00), warnText = Color(0xFFA55A00),
    banner = Color(0xFFE2F3F9), line = Color(0xFFDCE2EA), rail = Color.White,
)

val DarkRo = RoColors(
    dark = true,
    bg = Color(0xFF00224A), surface = Color(0xFF0A3D7A), field = Color(0xFF002C5F),
    ink = Color.White, sub = Color(0xFFA9BCD6),
    accent = ActiveBlue, onAccent = HyundaiNavy, accentText = ActiveBlue,
    warn = Color(0xFFF2A900), onWarn = HyundaiNavy, warnText = Color(0xFFF2A900),
    banner = Color(0xFF00597A), line = Color(0xFF1B4F8A), rail = Color(0xFF001A3A),
)

val LocalRo = staticCompositionLocalOf { LightRo }

// 화면 코드에서 쓰는 이름 — 지금 테마의 색을 돌려준다
internal val Bg: Color @Composable get() = LocalRo.current.bg
internal val Card: Color @Composable get() = LocalRo.current.surface
internal val Field: Color @Composable get() = LocalRo.current.field
internal val Ink: Color @Composable get() = LocalRo.current.ink
internal val Sub: Color @Composable get() = LocalRo.current.sub
internal val Active: Color @Composable get() = LocalRo.current.accent
internal val OnAccent: Color @Composable get() = LocalRo.current.onAccent
internal val AccentText: Color @Composable get() = LocalRo.current.accentText
internal val Warn: Color @Composable get() = LocalRo.current.warn
internal val OnWarn: Color @Composable get() = LocalRo.current.onWarn
internal val WarnText: Color @Composable get() = LocalRo.current.warnText
internal val Banner: Color @Composable get() = LocalRo.current.banner
internal val Line: Color @Composable get() = LocalRo.current.line
internal val Rail: Color @Composable get() = LocalRo.current.rail

/** Material 기본 아이콘 경로 (아이콘 확장 라이브러리 없이 필요한 것만). */
private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(d), fill = SolidColor(Color.Black)).build()

object RoIcons {
    val Mic = icon("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Speaker = icon("speaker", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    val Car = icon("car", "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11H5z")
    val Notes = icon("notes", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z")
    val Receipt = icon("receipt", "M18,17H6v-2h12v2zM18,13H6v-2h12v2zM18,9H6V7h12v2zM3,22l1.5,-1.5L6,22l1.5,-1.5L9,22l1.5,-1.5L12,22l1.5,-1.5L15,22l1.5,-1.5L18,22l1.5,-1.5L21,22V2l-1.5,1.5L18,2l-1.5,1.5L15,2l-1.5,1.5L12,2l-1.5,1.5L9,2 7.5,3.5 6,2 4.5,3.5 3,2v20z")
    val Store = icon("store", "M20,4H4v2h16V4zM21,14v-2l-1,-5H4l-1,5v2h1v6h10v-6h4v6h2v-6h1zM12,18H6v-4h6v4z")
    val Tune = icon("tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    val Shield = icon("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z")
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Box = icon("box", "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,19H5V5h14v14z")
    val Pin = icon("pin", "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z")
    val Calendar = icon("cal", "M19,4h-1V2h-2v2H8V2H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM19,20H5V9h14v11z")
}

/** 카드 바탕 — 라이트에선 얇은 테두리로 바탕과 구분. */
fun Modifier.roCard(radius: Int = 20): Modifier = composed {
    val ro = LocalRo.current
    val shape = RoundedCornerShape(radius.dp)
    clip(shape).background(ro.surface).then(if (ro.dark) Modifier else Modifier.border(1.dp, ro.line, shape))
}
