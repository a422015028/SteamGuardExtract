# SteamGuardExtract

从 Steam Android App 运行时提取 Steam Guard 密钥的 LSPosed / Vector 模块，用于把你 Steam 账号的令牌迁移到 Aegis、Stratum 等第三方 2FA 应用（互操作用途）。

基于现代 libxposed API 102 编写，纯 Java，无 Kotlin 运行时依赖。

## 功能特性

- 挂钩 `javax.crypto.Cipher.doFinal`，截获 SecureStore 中 `guard_data` 的解密明文；
- 挂钩 `javax.crypto.spec.SecretKeySpec`，截获 HMAC 密钥原始字节（`shared_secret` / `identity_secret`）；
- 将密钥转换为 Aegis / Stratum 可直接导入的 Base32 格式并弹窗展示，支持一键复制；
- 完全静默：不写日志、不落盘、不弹提示 Toast，密钥只通过弹窗呈现。

## 工作原理

| Hook | 目标 | 时机 | 作用 |
| --- | --- | --- | --- |
| A | `Cipher.doFinal(byte[])` | after | 截获 `guard_data` JSON 明文（含 `shared_secret` / `identity_secret`） |
| B | `SecretKeySpec(byte[], String)` | before | 截获 HMAC 密钥原始字节（`HmacSHA1` / `HmacSHA256`） |

Hook 挂在 `javax.crypto.*`（由 boot classloader 提供），在 `onPackageReady` 阶段即可覆盖冷启动自动读取 `guard_data` 的场景。

弹窗由 Hook B 触发，展示的是按标准 RFC4648 Base32 编码后的密钥串，Aegis 选「Steam」类型直接粘贴即可。

## 使用

1. 安装支持 libxposed API 102 的框架（Vector，或新版 LSPosed）；
2. 安装本模块 APK，并在框架中启用模块、勾选作用域 `com.valvesoftware.android.steam.community`（Steam App）；
3. 已登录状态下打开 Steam App 内「获取 Steam Guard 码」页面（或确认一笔交易 / 登录推送）；
4. 弹出「Steam 安全密钥已捕获」对话框，点击「复制密钥」；
5. 在 Aegis / Stratum 中手动添加：类型选 `Steam`，粘贴密钥，保存；
6. 对比 Aegis 与 Steam App 内动态码是否一致，确认无误后即可停用 / 卸载本模块。

## 构建

环境要求：

- JDK 21
- Android SDK 37（`build-tools 37.0.0`）
- Gradle（使用仓库内的 wrapper）

Debug 包（无需签名）：

```bash
./gradlew assembleDebug
```

Release 包（需要本地签名配置）：

在 `app/` 下创建 `signing.properties`（该文件已被 `.gitignore` 忽略，不会提交）：

```properties
STORE_FILE=release.keystore
STORE_PASSWORD=<你的密码>
KEY_ALIAS=<别名>
KEY_PASSWORD=<密钥密码>
```

然后：

```bash
./gradlew assembleRelease
```

Release 构建会在打包后自动移除 AGP 注入的 `META-INF/com/android/build/gradle/app-metadata.properties`（重签名后处理），产物不携带任何构建元数据。

## 项目结构

```
SteamGuardExtract/
├── app/
│   ├── build.gradle.kts        # 构建脚本（含签名读取与元数据移除后处理）
│   ├── proguard-rules.pro      # R8 混淆规则
│   ├── signing.properties      # 本地签名（不提交，需自行创建）
│   └── src/main/
│       ├── java/io/github/a422015028/steamguardextract/
│       │   ├── SteamGuardEntry.java  # 模块入口与 hook 挂载
│       │   ├── GuardCore.java        # 截获逻辑主体
│       │   └── GuardUi.java          # 密钥弹窗
│       └── resources/META-INF/xposed/ # java_init.list / scope.list / module.prop
├── .gitignore
└── settings.gradle.kts
```

## 安全警告

- Steam Guard 密钥等同于账号的第二因子。泄露密钥即等于泄露动态码能力，请勿截图、外发或在不可信设备上使用；
- 导入第三方 2FA 应用后，密钥同时存在于 Steam App 与第三方应用中，请自行评估并妥善保管；
- 本项目不联网、不收集、不上传任何数据，密钥仅在你的设备本地呈现。

## 免责声明

本项目仅用于你自己账号的密钥备份与互操作。请勿用于非法获取他人账号或违反 Steam 服务条款的用途，由此产生的一切后果由使用者自行承担。
