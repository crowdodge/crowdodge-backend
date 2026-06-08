package com.crowdodge.shared.kernel

/**
 * 地理座標 VO。PostGIS の `point` 型に対応する（users.home / event_destinations.destination_point, §1/§5）。
 * 順序は PostGIS の `point(longitude, latitude)` に合わせる。
 */
data class Location(val longitude: Double, val latitude: Double) {
    private companion object {
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
    }

    init {
        require(longitude in MIN_LONGITUDE..MAX_LONGITUDE) { "longitude は -180..180 の範囲: $longitude" }
        require(latitude in MIN_LATITUDE..MAX_LATITUDE) { "latitude は -90..90 の範囲: $latitude" }
    }
}
