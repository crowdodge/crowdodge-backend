-- crowdodge ベースラインマイグレーション（§14 step1）
--
-- 実テーブル定義は各 BC 着手時に Vxxx__<bc>_*.sql として追加する。
-- 命名規約（§12）: テーブル/列は snake_case、主キーは <単数テーブル名>_uuid。
-- BC 境界はテーブル接頭辞で表現（user_ / event_ / notification_）。
--
-- 自宅・目的地座標（users.home / event_destinations.destination_point）は
-- PostGIS の point 型を使うため拡張を有効化する（§1）。
CREATE EXTENSION IF NOT EXISTS postgis;
