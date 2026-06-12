package com.crowdodge.shared.infra.db

import com.crowdodge.shared.kernel.Location
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * PostGIS `geography(Point,4326)` 列を shared:kernel の [Location] にマップするカスタム型（§5.4）。
 * 座標は実世界の WGS84 緯度経度。route_duration 推定が地表距離（`ST_Distance` がメートル）を
 * 要するため、ユークリッド距離になるネイティブ `point` ではなく `geography` を採用する。
 * 座標順は PostGIS の `POINT(lon lat)`（[Location] の longitude, latitude 順）。
 */
class GeographyPointColumnType : ColumnType<Location>() {
    override fun sqlType(): String = "geography(Point,4326)"

    // 暫定: WKT(POINT(lon lat)) で出力。実際のバインド（EWKB 等）は各 BC 着手時に確定する（§1）。
    override fun notNullValueToDB(value: Location): Any = "POINT(${value.longitude} ${value.latitude})"

    override fun valueFromDB(value: Any): Location =
        when (value) {
            is Location -> value
            is String -> parseWkt(value)
            else -> error("geography 値を Location に変換できません: ${value::class}")
        }

    // 暫定の WKT パース（POINT(lon lat)）。
    private fun parseWkt(wkt: String): Location {
        val coords = wkt.substringAfter('(').substringBefore(')').trim().split(' ')
        return Location(longitude = coords[0].toDouble(), latitude = coords[1].toDouble())
    }
}

/** `geography(Point,4326)` 列（[Location]）を登録する（§5.4）。 */
fun Table.geographyPoint(name: String): Column<Location> = registerColumn(name, GeographyPointColumnType())
