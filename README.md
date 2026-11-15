# MC Client MCP

Claude Code が MCP 経由で Minecraft クライアントを操作し、Paper プラグインの実機検証を人手なしで行うための Fabric クライアント mod です。人がクライアントで行っていた「操作」と「見て確かめる」を、MCP のツール呼び出しに置き換えます。

- Minecraft 1.21.8 / Fabric Loader 0.16 以上 / Fabric API
- mod の中で MCP サーバー(Streamable HTTP)が `127.0.0.1` で待ち受けます。外部のブリッジは不要です
- 操作はクライアントの相互作用処理(`MultiPlayerGameMode`)を通すので、到達距離・視線・クライアント側の予測は人が操作したときと同じです

## 起動

JDK 21 が必要です。初回は Minecraft 本体とアセットのダウンロードで数分かかります。

```sh
# オフラインモードのサーバー向け。同じ名前なら UUID は毎回同じ
scripts/launch-client.sh --username Tester

# 実アカウントでログインする(オンラインモードのサーバー向け)
scripts/launch-client.sh --real-account

# 停止
scripts/stop-client.sh
```

`launch-client.sh` はクライアントをバックグラウンドで起動し、MCP の待ち受けが始まるまで待って、次の 1 行を出力します。

```json
{"url":"http://127.0.0.1:25580/mcp","pid":7068,"instance":"default","log":".../run/default/mc-client-mcp/launch.log"}
```

| オプション | 内容 |
|---|---|
| `--instance NAME` | クライアントの名前。ゲームディレクトリが `run/NAME` になる(既定 `default`) |
| `--port PORT` | MCP のポート(既定 25580)。クライアントごとに変える |
| `--username NAME` | オフラインのプレイヤー名 |
| `--real-account` | DevAuth で Microsoft アカウントにログインする。初回だけ `launch.log` に出る URL をブラウザで開いて認証する。以後はトークンが `~/.devauth` に保存される |
| `--minimized` | 起動完了後にウィンドウを最小化する |
| `--timeout SECONDS` | 起動完了を待つ秒数(既定 300) |

起動完了は `GET http://127.0.0.1:PORT/health` の `"ready":true` か、`run/NAME/mc-client-mcp/ready.json` の有無でも判定できます。

2 つ同時に動かす例(土地の所有者と他人など):

```sh
scripts/launch-client.sh --instance owner --username Owner --port 25580
scripts/launch-client.sh --instance guest --username Guest --port 25581
```

通常のランチャーで使う場合は、`./gradlew build` で出来る `build/libs/mc-client-mcp-0.1.0.jar` を Fabric の 1.21.8 プロファイルの `mods` に Fabric API と一緒に置きます。ポートは JVM 引数 `-Dmc-client-mcp.port=...` で変えられます。

## Claude Code への登録

```sh
claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp
```

または `.mcp.json`:

```json
{
  "mcpServers": {
    "minecraft": { "type": "http", "url": "http://127.0.0.1:25580/mcp" }
  }
}
```

## ツール

引数の詳細は各ツールの説明(`tools/list`)にあります。

| 分類 | ツール | できること |
|---|---|---|
| 接続 | `status` `connect` `disconnect` `respawn` | 接続・切断・再接続、接続状態とキック理由の取得 |
| プレイヤー | `player_state` `inventory` `select_slot` | 座標・向き・ゲームモード、インベントリ(`custom_data` を含む)、ホットバーの選択 |
| 視点と移動 | `look_at` `move_to` `jump` `set_sneak` `set_flying` `fall` | ブロックの面の中の位置へ視点を向ける、短距離の歩行、足場からの落下 |
| ブロック | `raycast` `use_on_block` `attack_block` `break_block` `place_block` `use_item` | 右クリック・左クリック・破壊・設置と、サーバーに戻されたかの判定 |
| エンティティ | `use_on_entity` `attack_entity` | エンティティの右クリック・左クリック |
| GUI | `screen` `click_slot` `close_screen` `wait_screen` `click_button` | 画面の中身の取得、スロットのクリック、開閉の待機 |
| チャット | `send_chat` `send_command` `messages` `wait_message` | チャットとコマンドの送信、受け取ったメッセージの取得と待機 |
| 観測 | `get_block` `entities` `screenshot` | クライアントから見たブロックとエンティティ(パケットだけで送られたものを含む)、スクリーンショット |
| イベント | `events` `wait_event` `wait_ticks` | クライアントが受け取った変化の時系列の取得と待機 |
| タイミング | `run_sequence` | 複数の操作を tick 単位の間隔でまとめて実行 |
| クライアント | `set_input_lock` `set_window` | 実入力のロック、ウィンドウの最小化 |

### tick と event_seq

時間の単位はクライアントの tick(1 秒 = 20 tick)です。すべての結果に `tick`(実行した tick)と `event_seq` が付きます。`event_seq` はその呼び出しが効く直前のイベント位置なので、`after_seq` に渡すと「その操作が引き起こしたこと」だけを取れます。

```
send_command {"command": "myplugin reload"}                → {"sent": "/myplugin reload", "event_seq": 120}
wait_message {"pattern": "リロードしました", "after_seq": 120}
```

「何も起きなかった」ことは、`wait_ticks` で待ったあと `events {"after_seq": 120}` が空であることで確かめられます。

### ブロックへの操作

`use_on_block` などは、指定した面の位置に視点を向け、照準が本当にそこに当たることを確かめてからクリックします。手前に松明があるなど別のものに当たる場合は、操作せずに `target_mismatch` を返し、当たったものを `details.hit` で知らせます(`on_mismatch: "use_hit"` で当たったものを操作することもできます)。

面の中の位置 `face_pos` は `[u, v]`(各 0〜1、ブロックのマス基準)です。

| 面 | u | v |
|---|---|---|
| `up` / `down` | x | z |
| `north` / `south` | x | y |
| `east` / `west` | z | y |

操作のあとはサーバーの確認応答を待ち、クライアントが予測したブロックの変化をサーバーが受け入れたかを `outcome` で返します。

| `outcome` | 意味 |
|---|---|
| `applied` / `broken` / `placed` | サーバーが受け入れた |
| `reverted` | サーバーが戻した(壊したブロックが戻る、置いたブロックが消える) |
| `changed` | サーバーが予測とは別の状態にした |
| `no_change` | サーバー側には元々その変化の対象が無かった(未確定の予測ブロックを壊した場合など) |
| `superseded` | 同じブロックへの後続の操作と確認応答がまとまって届き、切り分けられない。後続の操作の結果で判断する |
| `no_prediction` / `not_broken` / `not_placed` | クライアント側で何も変化しなかった |
| `unacknowledged` | 確認応答が時間内に来なかった |

同じ内容はイベントにも残ります。`block_change` の `source` が `prediction` ならクライアントの予測、`server_correction` ならサーバーによる巻き戻しです。

### run_sequence

MCP の 1 往復は数 tick かかるので、間隔が問題になる操作は 1 回の呼び出しにまとめます。各ステップは、前のステップの操作が終わってから `delay` tick 後(0 なら同じ tick)に始まります。

「植えて 1 tick 後に壊す」を 4 tick おきに 20 回:

```json
{
  "steps": [
    {"tool": "use_on_block", "args": {"pos": [-4, -61, 5], "face": "up"}},
    {"tool": "break_block", "args": {"pos": [-4, -60, 5]}, "delay": 1}
  ],
  "repeat": 20,
  "interval": 3
}
```

結果には各ステップの開始 tick・操作が終わった tick・結果(またはエラー)が入ります。既定では失敗したステップで打ち切ります(`"on_error": "continue"` で続行)。

## 設定

`run/NAME/config/mc-client-mcp.json`。起動ごとに JVM 引数 `-Dmc-client-mcp.<名前>=<値>` で上書きできます。

| 名前 | 既定 | 内容 |
|---|---|---|
| `port` | 25580 | MCP のポート。0 なら空きポート(`ready.json` に書かれる) |
| `allowedHosts` | `[]` | 追加で接続を許すサーバー。IP、CIDR、ホスト名 |
| `lockInput` | true | ワールド内では実際のキーボードとマウスを無視する |
| `startMinimized` | false | 起動完了後に最小化する |
| `acceptResourcePacks` | true | サーバーのリソースパックを確認画面なしで受け入れる |
| `eventBufferSize` | 50000 | メモリに保持するイベント数 |
| `callTimeoutMs` | 300000 | 1 回の呼び出しの上限時間 |

## 記録

- `run/NAME/mc-client-mcp/logs/<起動日時>/events.jsonl` — クライアントが観測したすべてのイベント
- `run/NAME/mc-client-mcp/logs/<起動日時>/calls.jsonl` — ツール呼び出しの引数と結果
- `run/NAME/mc-client-mcp/screenshots/` — `screenshot` の PNG

## 安全のための制限

- MCP は `127.0.0.1` のみで待ち受け、ブラウザからの別オリジンのリクエストは拒否します
- 接続先はループバックとプライベートアドレスだけです。それ以外は `allowedHosts` に書いたものに限ります。この制限は接続処理そのものに掛かっているので、マルチプレイ画面からの手動接続にも効きます
- 認証トークンは MCP の応答にもログにも出しません

## テスト用サーバー側の注意

- Paper は同じ IP からの 4 秒以内の再接続を拒否します(`Connection throttled!`)。複数クライアントや再接続を試すときは `bukkit.yml` の `settings.connection-throttle` を `-1` にします
- OP・ゲームモード・`tp`・`give`・`setblock` などの準備は、今までどおり RCON で行います

## 未対応・未確認

- 未対応: 経路探索による移動、金床や看板への文字入力、コマンド補完の取得
- 未確認: `--real-account` でのログイン(ブラウザでの認証が要るため)、リソースパックの自動受け入れ、通常のランチャーに jar を入れての起動、Linux での動作。それ以外は macOS 上で Paper 1.21.8 に接続して確認済みです

## ライセンス

[MIT License](LICENSE) です。著作権表示とライセンス文を残せば、利用・改変・再配布は自由です。

## 商標について

Minecraft は Mojang Studios の商標です。この mod は Minecraft の公式製品ではなく、Mojang および Microsoft の承認を受けたものでも、関係するものでもありません。
