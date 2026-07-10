# 日時とタイムゾーン

## 更新対象

- 一時点・日付の表現、業務タイムゾーン、タイムゾーン変換、Google Calendar終日予定の扱いを変更した場合に更新する。

## 基本ルール

- 一時点は `kotlin.time.Instant` で表す。
- 業務日付は `kotlinx.datetime.LocalDate` で表す。
- 業務日付の基準タイムゾーンは `Asia/Tokyo` とする。
- タイムゾーン変換は `shared/kernel` の `AppTime` に集約する。

## Google Calendar終日予定

- 終日予定は `LocalDate` の半開区間 `[startDate, endDate)` として扱う。
- 終日予定を `Instant` に変換する場合は、業務タイムゾーンの日付境界を使う。
