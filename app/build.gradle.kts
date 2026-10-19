import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

// 纯 Java 模块：排除 AGP 9 内置 Kotlin 支持塞进运行时 classpath 的 kotlin-stdlib
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

android {
    namespace = "io.github.a422015028.steamguardextract"

    // io.github.libxposed:api:102.0.0 的 aar-metadata 强制 minCompileSdk=37
    // API 37 仅以 platforms;android-37.0 发布(无 android-37), 必须显式 minor=0 才能解析
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.a422015028.steamguardextract"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.1.0"
    }

    // 从本地 signing.properties 读取 release 签名（该文件与 release.keystore 均被
    // .gitignore 忽略，不提交）。文件不存在时 releaseSigning 为 null，release
    // 回退为默认(debug)签名，保证他人 clone 后仍可构建。
    val releaseSigning = file("signing.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("STORE_FILE"))
                storePassword = releaseSigning.getProperty("STORE_PASSWORD")
                keyAlias = releaseSigning.getProperty("KEY_ALIAS")
                keyPassword = releaseSigning.getProperty("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        getByName("release") {
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // AGP 8.3+ 默认把 Git revision 写进
            // META-INF/version-control-info.textproto，发布包不需要
            vcsInfo {
                include = false
            }
            // R8 裁剪 + 全量混淆（含入口类名），java_init.list 由
            // -adaptresourcefilecontents 同步改写
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.version"
        }
    }
}

dependencies {
    // compileOnly：libxposed API 102 桩，绝不允许打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    // 仅编译期注解（@NonNull / @Nullable）
    compileOnly("androidx.annotation:annotation:1.9.1")
}

// =============================================================================
// 移除 AGP 强制注入的 app-metadata.properties（重签名后处理）
// =============================================================================
// META-INF/com/android/build/gradle/app-metadata.properties 由 packageRelease
// 直接注入 APK，绕过资源合并流程，packaging.resources.excludes 对其无效
// （AGP 9.2.1 实测确认），也没有官方 DSL 可以关闭。
// version-control-info.textproto 已由 buildType 的 vcsInfo { include = false }
// 从源头关闭，不需要在这里处理。
// 方案：在 packageRelease 之后用 Zip API 从 APK 中删除该 entry，
//       再用 zipalign + apksigner 重新对齐和签名。
// 仅在 release 签名可用（signing.properties 存在）时执行，否则跳过。
// =============================================================================

// 需要从 APK 中移除的文件
private val metadataToStrip = setOf(
    "META-INF/com/android/build/gradle/app-metadata.properties"
)

// 在 doLast 中执行外部命令（Gradle exec 在 doLast 闭包中不可用，改用 ProcessBuilder）
fun runCommand(vararg args: String) {
    val process = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val output = process.inputStream.bufferedReader().readText()
        throw GradleException("命令执行失败 (exit=$exitCode): ${args.joinToString(" ")}\n$output")
    }
}

// 从 local.properties 或环境变量读取 Android SDK 路径
fun findSdkDir(): java.io.File {
    val localProps = file("${rootDir}/local.properties")
    if (localProps.exists()) {
        val props = Properties()
        FileInputStream(localProps).use { props.load(it) }
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null) {
            return file(sdkDir.replace("\\:", ":"))
        }
    }
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (env != null) {
        return file(env)
    }
    throw GradleException("找不到 Android SDK 路径，请设置 local.properties 中的 sdk.dir 或 ANDROID_HOME")
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doLast {
        // release 未配置签名（signing.properties 缺失）时无法重签，跳过
        if (android.signingConfigs.findByName("release") == null) {
            return@doLast
        }
        val apk = outputs.files.asFileTree.files.first { it.extension == "apk" }
        val sdkDir = findSdkDir()
        val btDir = file("$sdkDir/build-tools/${android.buildToolsVersion}")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val zipalign = file("$btDir/${if (isWindows) "zipalign.exe" else "zipalign"}")
        val apksigner = file("$btDir/${if (isWindows) "apksigner.bat" else "apksigner"}")
        val signing = android.signingConfigs.getByName("release")

        val stripped = file("${apk.parentFile}/${apk.nameWithoutExtension}-stripped.apk")
        val aligned = file("${apk.parentFile}/${apk.nameWithoutExtension}-aligned.apk")

        try {
            // 1. 从 APK 中删除无用的元数据 entry
            val zipIn = ZipFile(apk)
            try {
                val zipOut = ZipOutputStream(stripped.outputStream())
                try {
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name !in metadataToStrip) {
                            val newEntry = ZipEntry(entry.name)
                            newEntry.time = entry.time
                            // 保持原始压缩方法：Android 11+ 要求 resources.arsc
                            // 未压缩（STORED）并在 4 字节边界对齐，否则安装失败
                            if (entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.crc = entry.crc
                                newEntry.compressedSize = entry.size
                            } else {
                                newEntry.method = ZipEntry.DEFLATED
                            }
                            zipOut.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                zipIn.getInputStream(entry).copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                } finally {
                    zipOut.close()
                }
            } finally {
                zipIn.close()
            }

            // 2. zipalign 对齐
            runCommand(zipalign.absolutePath, "-f", "4", stripped.absolutePath, aligned.absolutePath)

            // 3. apksigner 重新签名（覆盖原始 APK）
            runCommand(
                apksigner.absolutePath, "sign",
                "--ks", signing.storeFile!!.absolutePath,
                "--ks-pass", "pass:${signing.storePassword!!}",
                "--ks-key-alias", signing.keyAlias!!,
                "--key-pass", "pass:${signing.keyPassword!!}",
                "--out", apk.absolutePath,
                aligned.absolutePath
            )
        } finally {
            stripped.delete()
            aligned.delete()
        }

        logger.lifecycle("已从 APK 中移除 AGP 构建元数据: ${metadataToStrip.joinToString()}")
    }
}
