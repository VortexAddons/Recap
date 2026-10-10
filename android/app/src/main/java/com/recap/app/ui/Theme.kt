package com.recap.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Material 3 with dynamic color (wallpaper-derived palette), rounder shapes. */
@Composable
fun RecapTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}

object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeF = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayF = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeF)
    fun dayKey(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun day(d: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(dayF)
        }
    }

    fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 1440 -> "${m / 60} h ago"
            else -> "${m / 1440} d ago"
        }
    }

    fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
