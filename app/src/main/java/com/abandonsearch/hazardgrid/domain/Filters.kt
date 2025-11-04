package com.abandonsearch.hazardgrid.domain

enum class SortOption(val id: String) {
    RELEVANCE("relevance"),
    DISTANCE("distance"),
    RATING("rating"),
    SECURITY("security");
}

enum class ScaleFilter(val id: String) {
    ANY("any"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    UNKNOWN("unknown"),
}

enum class FloorsFilter(val id: String) {
    ANY("any"),
    LOW("low"),
    MID("mid"),
    HIGH("high"),
    TOWER("tower"),
    UNKNOWN("unknown"),
}

enum class AgeFilter(val id: String) {
    ANY("any"),
    NEW("new"),
    RECENT("recent"),
    CLASSIC("classic"),
    HERITAGE("heritage"),
    UNKNOWN("unknown"),
}

enum class RatingFilter(val id: String, val minValue: Double?) {
    ANY("any", null),
    FOUR_PLUS("4", 4.0),
    SIX_PLUS("6", 6.0),
    EIGHT_PLUS("8", 8.0),
    NINE_PLUS("9", 9.0),
    UNKNOWN("unknown", null);
}

data class FilterState(
    val query: String = "",
    val floors: FloorsFilter = FloorsFilter.ANY,
    val security: ScaleFilter = ScaleFilter.ANY,
    val interior: ScaleFilter = ScaleFilter.ANY,
    val age: AgeFilter = AgeFilter.ANY,
    val rating: RatingFilter = RatingFilter.ANY,
    val sort: SortOption = SortOption.RELEVANCE,
)

fun FilterState.hasActiveFilters(): Boolean = floors != FloorsFilter.ANY ||
    security != ScaleFilter.ANY ||
    interior != ScaleFilter.ANY ||
    age != AgeFilter.ANY ||
    rating != RatingFilter.ANY
