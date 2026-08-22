# HTML5 ビュアー (Android)

端末内のローカルHTMLファイルを開いて表示するシンプルなAndroidアプリです。

## 機能

- フォルダを選択(Storage Access Framework)すると、その中を再帰的に検索して
  `.html` / `.htm` ファイルを一覧表示します。
- 一覧からファイルをタップするとWebViewで開きます。
- 同じフォルダ内にある画像・CSS・JSなど、相対パスで参照されるリソースも
  正しく読み込めます(SAF経由でファイルを解決しています)。
- JavaScript・DOM Storageを有効化しているので、一般的なHTML5コンテンツ
  (Canvas, ローカルストレージを使うWebアプリなど)を表示できます。
- 前回選択したフォルダを記憶し、次回起動時に自動で読み込みます。
- WebView内の戻る操作やアプリの戻るボタンでページ履歴・一覧画面を辿れます。

## 開かない機能・制限

- `file://` へのアクセスは使っていません(SAFのみ)。外部ストレージの
  READ権限も不要です。
- ネットワーク上のURLは扱いません(あくまでローカルHTMLビュアーです)。
- 大きなフォルダを選ぶとファイル一覧のスキャンに時間がかかることがあります。

## Android Studioを使わずにAPKを作る方法(GitHub Actions)

Android Studioのセットアップが難しい場合、GitHubの無料アカウントさえ
あれば、ブラウザ操作だけでAPKをビルドできます。
(`.github/workflows/build-apk.yml` にビルド設定を同梱済みです)

### 署名付きAPKを自動生成する場合

リリース配布用の署名付きAPKを作るには、GitHubリポジトリの
`Settings` → `Secrets and variables` → `Actions` で次の4つを登録します。

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

生成手順は以下の通りです。

1. ローカル環境で `keytool` を使ってキーストアを作成
   `keytool -genkeypair -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key`
2. キーストアを Base64 で変換し、`KEYSTORE_BASE64` として登録
3. `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` を登録
4. GitHub の `Actions` で `Build signed APK` を実行
5. 成功すると `app-release-apk` のアーティファクトから `app-release.apk` をダウンロード

アプリの署名に使うキーストアは必ずバックアップしておいてください。

1. https://github.com で無料アカウントを作成(すでにお持ちならスキップ)。
2. 右上の「+」→「New repository」で新しいリポジトリを作成
   (Public / Privateどちらでも可)。
3. 作成したリポジトリの画面で「uploading an existing file」
   (または「Add file」→「Upload files」)を選び、この
   `Html5Viewer` フォルダの中身を**まるごと**ドラッグ&ドロップして
   アップロードし、「Commit changes」。
   - フォルダごとドラッグすると中身が展開されてアップロードされます。
   - `.github` フォルダ(隠しフォルダ)もOSによっては見えにくいので、
     見当たらない場合は隠しファイル表示をオンにしてください。
4. リポジトリ上部の「Actions」タブを開く。
   - 自動的に「Build APK」というワークフローが実行され始めます
     (開始しない場合は左側の「Build APK」を選び、
     「Run workflow」ボタンを押す)。
5. 実行が完了する(緑のチェックマークが付く)まで数分待つ。
6. 完了した実行をクリックして開き、下の方にある「Artifacts」欄の
   「app-debug-apk」をクリックしてダウンロード(ZIPファイル)。
7. ダウンロードしたZIPを解凍すると `app-debug.apk` が出てくるので、
   それをスマホに転送(Google Driveやメール、LINEなど)してインストール。
   - インストール時、スマホ側で「提供元不明のアプリ」または
     「このアプリからのインストールを許可」を、ファイルを開くアプリに
     対して有効にする必要があります。



このプロジェクトは `compileSdk = 37` / `targetSdk = 37`(Android 17、
2026年6月リリース)に設定済みです。AGPは8.9.1、Kotlinは2.0.21を指定して
います(compileSdk 37を使うにはAGP 8.9.0以上が必須)。

1. Android Studio Narwhal以降(Android 17 SDKをサポートするバージョン)
   でこのフォルダを開く(`File > Open` でこのディレクトリを選択)。
2. `Tools > SDK Manager > SDK Platforms` タブで
   「Android 17.0 ("Cinnamon Bun")」のプラットフォームと、
   `SDK Tools` タブで `Android SDK Build-Tools 37.x.x` をインストール
   しておく。
3. Gradle Sync を実行(Gradle Wrapper はこのZIPに含めていないため、
   Android Studioが自動的に補完します)。
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)` でAPKを生成、
   または実機/エミュレータ(Android 7.0 / API 24 以上)で直接実行。

コマンドラインでビルドする場合は、事前に
`gradle wrapper --gradle-version 8.10` などAGP 8.9系に対応する
Gradleバージョンでラッパーを生成してから `./gradlew assembleDebug` を
実行してください。

### 注意

- この開発用サンドボックス環境にはAndroid SDKやネットワークアクセスが
  ないため、私(Claude)の側でAPKファイルそのものを生成することは
  できません。上記の手順でご自身の環境(Android Studio)でビルドして
  ください。
- minSdkは24のままなので、生成したAPKはAndroid 7.0以降の端末で動作し、
  Android 17端末では新しいランタイム挙動(タブレット等でのアダプティブ
  UI必須化など)が適用されます。今回のアプリはレイアウトが単純なため、
  大きな影響はないはずです。

## プロジェクト構成

```
Html5Viewer/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/html5viewer/MainActivity.kt
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   └── list_item_html_file.xml
            ├── values/
            │   ├── strings.xml
            │   ├── themes.xml
            │   ├── bools.xml       (two_pane = false, スマホ用デフォルト)
            │   └── dimens.xml
            ├── values-w600dp/
            │   └── bools.xml       (two_pane = true, 600dp以上で2ペイン表示)
            └── values-night/
                └── themes.xml
```

`bools.xml`は同名ファイルが`values`と`values-w600dp`の2箇所に存在し、内容が異なる(2ペイン表示を切り替える)点に注意してください。`values`側は`two_pane = false`、`values-w600dp`側は`true`です。

## カスタマイズのヒント

- `applicationId` / パッケージ名は `com.example.html5viewer` になっています。
  実際に配布する場合は自分のドメインに合わせて変更してください。
- アプリアイコンは未設定です(デフォルトアイコンが使われます)。
  `res/mipmap-*` に `ic_launcher` を追加すると独自アイコンにできます。
- 単一ファイル(フォルダではなく1ファイル)を選ばせたい場合は
  `ActivityResultContracts.OpenDocument()` に変更できますが、その場合
  同じフォルダ内の相対リソース読み込みはできなくなります。
