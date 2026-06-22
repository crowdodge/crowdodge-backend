package com.crowdodge.shared.infra.db

import com.crowdodge.shared.kernel.Location
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PostGIS `geography(Point,4326)` 列を shared:kernel の [Location] にマップするカスタム型（§5.4）。
 * 座標は実世界の WGS84 緯度経度。route_duration 推定が地表距離（`ST_Distance` がメートル）を
 * 要するため、ユークリッド距離になるネイティブ `point` ではなく `geography` を採用する。
 * 座標順は PostGIS の `POINT(lon lat)`（[Location] の longitude, latitude 順）。
 *
 * 往復は ColumnType 内で完結する（r2dbc-postgresql / PostGIS で実測）:
 * - 書き: パラメータを `geography` 列へ直接 bind すると 42804（型不一致）になるため、[parameterMarker] で
 *   プレースホルダを `CAST(? AS geography)` にラップし、値は EWKT 文字列（[notNullValueToDB]）で渡す。
 * - 読み: `geography` 列は EWKB の16進文字列（例 `0101000020E6100000...`）で返るため [valueFromDB] でデコードする。
 */
class GeographyPointColumnType : ColumnType<Location>() {
    override fun sqlType(): String = "geography(Point,4326)"

    // 値は EWKT 文字列として bind し、プレースホルダ側（parameterMarker）で geography へキャストする。
    override fun notNullValueToDB(value: Location): Any =
        "SRID=4326;POINT(${value.longitude} ${value.latitude})"

    // geography 列はテキスト/バイナリの直接 bind を受け付けない（42804）ため、明示キャストを噛ませる。
    override fun parameterMarker(value: Location?): String = "CAST(? AS geography)"

    override fun valueFromDB(value: Any): Location =
        when (value) {
            is Location -> value
            is String -> decodeEwkb(hexToBytes(value))
            is ByteArray -> decodeEwkb(value)
            is ByteBuffer -> decodeEwkb(ByteArray(value.remaining()).also { value.get(it) })
            else -> error("geography 値を Location に変換できません: ${value::class}")
        }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(radix = 16).toByte() }

    /**
     * EWKB の Point をデコードする。レイアウト: [byteOrder(1)][type+flags(4)]([SRID(4)] if SRID flag)[X(8)][Y(8)]。
     * X=経度, Y=緯度。SRID は本列では常に 4326 のため読み飛ばす。
     */
    private fun decodeEwkb(bytes: ByteArray): Location {
        val buf = ByteBuffer.wrap(bytes)
        buf.order(if (bytes[0].toInt() == 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        buf.get() // byte order マーカ
        val type = buf.int
        if ((type and SRID_FLAG) != 0) buf.int // SRID（4326 固定のため読み飛ばす）
        val longitude = buf.double
        val latitude = buf.double
        return Location(longitude = longitude, latitude = latitude)
    }

    private companion object {
        const val SRID_FLAG = 0x20000000
    }
}

/** `geography(Point,4326)` 列（[Location]）を登録する（§5.4）。書き込み・読み出しの往復は ColumnType が担う。 */
fun Table.geographyPoint(name: String): Column<Location> = registerColumn(name, GeographyPointColumnType())
