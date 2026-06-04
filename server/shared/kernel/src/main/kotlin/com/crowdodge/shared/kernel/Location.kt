package com.crowdodge.shared.kernel

/**
 * 地理座標 VO。PostGIS の `point` 型に対応する（users.home / event_destinations.destination_point, §1/§5）。
 * 順序は PostGIS の `point(longitude, latitude)` に合わせる。
 */
data class Location(val longitude: Double, val latitude: Double) {
    init {
        require(longitude in -180.0..180.0) { "longitude は -180..180 の範囲: $longitude" }
        require(latitude in -90.0..90.0) { "latitude は -90..90 の範囲: $latitude" }
    }
}
