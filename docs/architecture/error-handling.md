# エラーハンドリング

## 更新対象

- Arrow の使い方、エラー型、HTTPエラー形式を変更した場合に更新する。

## 基本ルール

- 想定内のドメイン失敗は型で表す。
- 想定外の障害、インフラ障害、外部SDK例外は例外として扱う。
- ドメイン失敗の公開戻り値は `Either<Error, Result>` とする。
- `kotlin.Result` はドメイン失敗の表現に使わない。

## Arrow

- `domain` と `application` の内部処理は `Raise<E>` を使う。
- レイヤー境界の公開戻り値で `either {}` により `Either<E, A>` へ確定する。
- 既定は fail-fast とする。
- 入力一括検証が必要な場合は `Raise<NonEmptyList<E>>` と `zipOrAccumulate` または `mapOrAccumulate` を使う。
- `either {}` 内で `Throwable` を握りつぶさない。
- `raise` の脱出を捕捉する可能性があるため、回復処理は Arrow の `recover` または `catch` を使う。

## BC境界

- 各BCは、自身が公開する失敗を自身のエラー型で表す。
- BC間でエラー型を共有しない。
- BC間を接続するACLは、提供元BCのエラーを利用側BCのエラーへ網羅的に変換する。
- 利用側BCは、提供元BCの実装詳細ではなく、自身のユースケースに必要な失敗の意味を定義する。
- バルク取得Portは、要求されたすべての識別子について成功または失敗を返す。
- Portが契約した結果を返さない場合は、想定内のドメイン失敗へ変換せず契約違反として扱う。
- 外部境界で検証済みの値がドメイン不変条件に違反した場合は、契約違反として処理を失敗させる。

## HTTP境界

- HTTPレスポンスのエラー形式は Problem Details に統一する。
- Problem Details の `code` は UPPER_SNAKE とし、クライアント分岐に使う。
- Problem Details の `type` は `https://crowdodge.grfsv.net/problems/{CODE}` から導出する。
- 入力一括検証などの詳細は拡張メンバー `violations` に入れる。
- `Either.Left` から Problem Details への変換は `presentation` に置く。
- エラー型から Problem Details への変換は `when` で網羅する。
- 外部API例外は `infrastructure` で捕捉し、境界用のエラー型へ変換する。
