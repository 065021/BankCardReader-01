# BankCardReader — 手机 NFC 读取银行卡号

使用手机 NFC 功能读取银行卡信息（卡号、有效期、卡组织），完全离线运行，不存储、不上传任何数据。

## 功能

- 读取银行卡号（PAN）
- 读取有效期（MM/YY）
- 识别卡组织（Visa / Mastercard / American Express / 银联 / JCB / Discover）
- 读取持卡人姓名（部分卡片支持）

## 支持的卡片

符合 EMV 标准的非接触式银行卡，包括：
- Visa（含 Visa Electron）
- Mastercard（含 Maestro）
- American Express
- 银联（UnionPay）
- JCB
- Discover
- Interac

## 构建和安装

### 环境要求

- **Android Studio** Hedgehog (2023.1) 或更新版本
- JDK 17
- Android SDK 34

### 构建步骤

1. 用 Android Studio 打开本项目目录
2. 等待 Gradle 同步完成
3. 连接 Android 手机（需开启 USB 调试）
4. 点击运行按钮

或生成 APK：

```bash
# 在项目根目录
./gradlew assembleDebug
# APK 位于 app/build/outputs/apk/debug/
```

### 安装 APK

将生成的 APK 传输到手机后直接安装。首次安装需要在系统设置中允许"安装未知来源应用"。

## 使用方法

1. 确保手机 **NFC 功能已开启**
2. 打开应用
3. 将银行卡贴在手机背面 NFC 感应区（通常在后置摄像头附近）
4. 保持卡片不动，等待读取完成
5. 查看显示的卡号和有效期

## 技术原理

应用通过 Android NFC API 的 `enableReaderMode` 与银行卡建立 ISO-DEP 连接，遵循 EMV 标准发送 APDU 命令读取卡片数据：

1. **SELECT PPSE** — 选择支付系统环境（2PAY.SYS.DDF01）
2. **SELECT AID** — 选择支付应用
3. **GPO**（Get Processing Options）— 获取处理选项和文件定位器
4. **READ RECORD** — 根据 AFL 读取应用数据记录
5. 从记录中解析 **TLV 编码**的数据，提取 PAN、有效期等字段

## 隐私声明

- 所有数据仅在本地处理，**不会**存储到设备
- **不会**通过网络传输任何卡片数据
- **不会**连接到任何远程服务器

## 注意事项

- 仅适用于 Android 设备，iOS 不开放 NFC 银行卡读取权限
- 不同银行的卡片实现可能有差异，部分卡片可能无法读取
- 无法读取 CVV 安全码（卡片芯片不通过非接触式接口提供此数据）
- 本应用仅供个人学习和技术研究使用

## 项目结构

```
BankCardReader/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bankcardreader/
│       │   ├── MainActivity.kt          # UI 和 NFC 生命周期
│       │   └── nfc/
│       │       ├── EmvReader.kt         # EMV 协议实现
│       │       ├── TlvParser.kt         # TLV 解析器
│       │       └── ByteArrayWriter.kt   # 字节数组写入工具
│       └── res/
│           ├── values/
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               └── nfc_tech_filter.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 开源参考

本项目的 EMV 协议实现参考了以下开源项目：

- [EMV-NFC-Paycard-Enrollment](https://github.com/devnied/EMV-NFC-Paycard-Enrollment) — Java EMV 卡片读取库
- [Talk to your Credit Card (Android NFC Java)](https://medium.com/@androidcrypto/talk-to-your-credit-card-android-nfc-java-d782ff19fc4a) — EMV 通信步骤详解

## 许可

MIT License
