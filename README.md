# muse morning call

Android 向けのローカル動作＆ AI エージェント連携対応モーニングコール／通話アプリです。  
端末単体でのアラーム着信・音声対話に加え、Tailscale 経由で外部の AI エージェントや TTS サーバ（Irodori-TTS 等）と接続することで、リアルタイムな双方向通話ができます。

## 主な機能

- **端末単体で完結するモーニングコール**
  - スクロール式の時刻ピッカーによる着信予約（1回 / 毎日）
  - 同梱音声（`morning.wav`・`canned/*.wav`・`fillers/*.wav`）と環境音（`ambient/*.wav`）による自然な着信・応答
  - `SpeechRecognizer`（日本語オフライン対応）＋ローカルコマンドルーティング（再再生・スピーカー切替・通話終了・時刻予約など）
- **AI エージェント連携（Tailscale + HTTP API）**
  - 端末側 HTTP サーバ（ポート `8791`、Tailscale `100.64.0.0/10` 帯域＋ `X-Token` 認証）
  - Long Polling（`GET /events`・`GET /utterance`）で発話を即時通知
  - 音声バイナリ送信（`POST /say`）またはローカル TTS サーバ経由のテキスト合成（`POST /say_text`）
  - リモート終話（`POST /hangup`）時は「通話が切れました」を表示して画面を保持

## ディレクトリ構成

- `app/src/main/` — Kotlin ソース（`dev.muse.fakecall`）、`AndroidManifest.xml`、リソース・音声アセット
- `build-windows.ps1` — Windows 用ビルドスクリプト（Gradle 不要：`kotlinc` → `aapt2` → `d8` → `zipalign` → `apksigner`）
- `manual-build.sh` — Linux / macOS 用ビルドスクリプト
- `send_call.sh` — エージェント側からの着信・発話送信スクリプト

## ビルド方法

### 1. 初回のみ署名用 `debug.keystore` を作成

公開リポジトリには秘密鍵を含めていないため、初回ビルド前にプロジェクト直下で生成してください。

```bash
keytool -genkey -v -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

### 2. APK ビルド & インストール

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
adb install -r .\manual-build\FakeCall.apk
```

**Linux / macOS:**
```bash
./manual-build.sh
adb install -r manual-build/FakeCall.apk
```

## AI エージェント / Tailscale 連携

1. アプリを起動し、**「受け口を開始」** をタップしてトークンをコピーします。
2. 送信側の `send_call.sh` と同じディレクトリに `token.txt` を作成し、トークンを貼り付けます。
3. 環境変数 `FAKECALL_HOST` に端末の Tailscale IP を指定して実行します。

```bash
export FAKECALL_HOST="<PHONE_TAILSCALE_IP>"
./send_call.sh --ping
```

### 端末側 HTTP サーバ API（ポート `8791`）

認証条件：送信元 IP が Tailscale 帯域（`100.64.0.0/10`）かつ `X-Token` ヘッダ一致。

| メソッド / パス | 用途 |
|---|---|
| `GET /ping` | ヘルスチェック |
| `GET /status` | 通話状態・予約情報の取得 |
| `GET /events?since=<seq>&timeout=<ms>` | 状態変化・ユーザー発話の Long Polling |
| `GET /utterance?since=<id>&timeout=<ms>` | 最新発話テキストの Long Polling |
| `POST /call` | 音声バイナリ付き着信の開始 |
| `POST /say` | 通話中の返答音声（WAV 等）の送信 |
| `POST /say_text` | 端末からローカル TTS サーバへテキスト合成を依頼して再生 |
| `POST /accept` | 発信中画面から通話状態へ移行 |
| `POST /hangup` | リモートから通話を終了 |
| `POST /schedule` | モーニングコール予約の更新・解除 |

## セキュリティ・ライセンスに関する注意

- `TriggerReceiver`（`dev.muse.fakecall.TRIGGER`）は ADB 連携向けに `exported="true"` で公開されています。外部アプリからのローカルブロードキャストを制限したい場合は `exported="false"` に変更してください。
- `app/src/main/assets/` 内の音声ファイルは **Irodori-TTS** の Voice Design モード（参照音声なし）で生成した合成音声です。音声の再配布や差し替えを行う場合は利用する TTS モデルのライセンスもあわせてご確認ください。
- コードは [MIT License](LICENSE) のもとで公開されています。
