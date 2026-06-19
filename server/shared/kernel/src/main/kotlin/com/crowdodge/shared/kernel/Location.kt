package com.crowdodge.shared.kernel

/**
 * 地理座標 VO。PostGIS の `point` 型に対応する（users.home / event_destinations.destination_point, §1/§5）。
 * 順序は PostGIS の `point(longitude, latitude)` に合わせる。
 */
data class Location(val longitude: Double, val latitude: Double) {
    init {
        require(isValid(longitude, latitude)) { "位置情報が範囲外: ($longitude, $latitude)" }
    }
    companion object {
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_LONGITUDE = 180.0
        private const val MIN_LATITUDE = -90.0
        private const val MAX_LATITUDE = 90.0

        private fun isValid(longitude: Double, latitude: Double): Boolean =
            longitude in MIN_LONGITUDE..MAX_LONGITUDE && latitude in MIN_LATITUDE..MAX_LATITUDE

        fun ofOrNull(longitude: Double, latitude: Double): Location? =
            if (isValid(longitude, latitude)) Location(longitude, latitude) else null
    }
}
