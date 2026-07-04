package com.example.heatradar.feature.trends

enum class TrendRange {
    OneHour,
    TwentyFourHours
}

data class TrendsUiState(
    val selectedRange: TrendRange = TrendRange.OneHour
)
