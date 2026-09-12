# KFileSyncMobile 代码审查修复总结

## 一、修复概览

基于 Kotlin **2.3.21** 版本，对项目进行了全量代码审查修复，共计 **23 项问题**，涉及 **30+ 个文件**的变更。

---

## 二、修复详情

### 1. 构建配置修复

#### 依赖版本升级 — [libs.versions.toml](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/gradle/libs.versions.toml)

| 依赖项 | 旧版本 | 新版本 | 说明 |
|--------|--------|--------|------|
| `androidx-core` | 1.18.0 | **1.19.0** | 最新 stable |
| `androidx-documentfile` | 1.0.1 | **1.1.0** | 最新 stable |
| `androidx-lifecycle` | 2.11.0-beta01 | **2.10.0** | beta → stable |
| `androidx-work` | 2.10.0 | **2.11.2** | 最新 stable |
| `kotlinxCoroutines` | 1.10.2 | **1.9.0** | K2.3 兼容 |
| `kotlinxSerialization` | 1.8.1 | **1.10.0** | K2.3 对应版本 |
| `kotlinxDatetime` | 0.7.1 | **0.8.0** | K2.3 对应版本 |
| `ktor` | 3.1.3 | **3.5.0** | K2.3 对应最新 stable |
| `sqldelight` | 2.1.0 | **2.3.2** | K2.3 对应最新 stable |
| `koin` | 4.1.0 | **4.2.1** | K2.3 对应最新 stable |
| `material3` | 1.11.0-alpha07 | *移除* | 改用 CMP 自带 |

#### 语法/拼写修复
- `version = "androidx-lifecycle"` → `version.ref = "androidx-lifecycle"`（语法错误）
- `andoridx-documentfile` → `androidx-documentfile`（拼写修复）
- BouncyCastle 注释中的 `Andorid`/`uises`/`andoridMain` → `Android`/`uses`/`androidMain`

#### JVM Target 统一 — [androidApp/build.gradle.kts](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/androidApp/build.gradle.kts)
- `JVM_11` → `JVM_17`（与 `sharedLogic` 保持一致）

---

### 2. 架构违规修复（六边形架构）

#### ❶ Domain → Infrastructure 违规：Indexer

**问题**：`Indexer.kt` 直接 import 了 `infrastructure.crypto.Blake3Hasher`、`StreamingSha256` 等。

**修复**：
- 新建 [HashPort.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/domain/port/HashPort.kt) — 域端口接口
- 新建 [DefaultHashPort.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/infrastructure/crypto/DefaultHashPort.kt) — 基础设施层适配器
- 修改 [Indexer.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/domain/service/Indexer.kt) — 通过构造函数注入 `HashPort`
- 修改 [SharedModule.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/di/SharedModule.kt) — 注册 `HashPort` 绑定

**结果**：领域层不再有任何 `infrastructure` 包 of import ✅

#### ❷ Application → Domain 反向依赖：PlatformFile

**问题**：`FileIO.kt`（域端口）import 了 `application.service.PlatformFile`。

**修复**：
- 将 `PlatformFile` data class 从 `TransferService.kt` 移入 [FileIO.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/domain/port/FileIO.kt)
- 更新 5 个消费文件的 import 路径

#### ❸ Domain → SQLDelight 依赖：Repository

**问题**：`Repository.kt`（域端口）import 了 `app.cash.sqldelight.db.QueryResult`。

**修复**：所有 `QueryResult<Long>` 返回类型改为 `Long`

#### ❹ Domain → Application 反向依赖：ConflictResolver

**问题**：`ConflictResolver.kt` 使用了 `application.service.ConflictResolution`。

**修复**：
- 新建 [ConflictResolution.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/domain/model/ConflictResolution.kt)（域模型层）
- 同时修复文件名拼写：`ConfilctResolver.kt` → `ConflictResolver.kt`

---

### 3. 代码一致性修复

#### DeviceState 错误类型
- `IllegalStateException` → `DomainError.InvalidStateTransition`（遵循 DDD 错误约定）

#### DebouncedFileWatcher 域纯度
- 移除 `Napier` import，改用可注入的 `onError: ((String) -> Unit)?` lambda

---

### 4. 代码重复消除

#### DevicePlatform.toWire() / fromWire()
- 将 `PairingService.kt` and `HttpServer.kt` 中的两份 private 副本合并到 [Device.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/domain/model/Device.kt) 的 `DevicePlatform` enum 上

#### pemToDer() + base64Decode()
- 3 份重复实现（HttpServer、PinnedTrustSnapshot、IosSecIdentityBridge）合并为 [PemUtils.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonMain/kotlin/com/kfilesync/mobile/infrastructure/crypto/PemUtils.kt)

---

### 5. 安全加固

#### SecurityHandler 级联清理
- 新增 `cancelTransfersForPeer(deviceId)` 方法到 `TransferAppService` 接口
- `onTrustRevoked()` 现在会取消被撤销设备的所有进行中传输

#### 移除重复 markPaired()
- `SecurityHandler` 不再订阅 `PairingCompleted` 事件（`PinnedTrustSnapshot.bind()` 已承担此职责）

---

### 6. Naming 修复

#### iOS TLS 目录
- `platform/tsl/` → `platform/tls/`（3 个文件移动 + package 声明更新）

---

### 7. UI 层解耦

#### SettingsViewModel
- `SettingsServiceImpl.EMPTY` → `SettingsSnapshot.EMPTY`（UI 层不再引用实现类）

---

### 8. 单元测试补充

| 测试文件 | 测试内容 |
|----------|----------|
| [VersionVectorTest.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonTest/kotlin/com/kfilesync/mobile/domain/VersionVectorTest.kt) | increment、isAncestorOf、conflictsWith、merge、equality（11 个测试） |
| [DeviceStateTest.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonTest/kotlin/com/kfilesync/mobile/domain/DeviceStateTest.kt) | 所有状态转换路径（6 个测试） |
| [ChunkingStrategyTest.kt](file:///Users/luokai/AndroidStudioProjects/KFileSyncMobile/sharedLogic/src/commonTest/kotlin/com/kfilesync/mobile/domain/ChunkingStrategyTest.kt) | 边界值：0、128K、256M、1G、16G 阈值（10 个测试） |

---

## 三、新增文件

| 文件 | 用途 |
|------|------|
| `domain/port/HashPort.kt` | 哈希运算域端口接口 |
| `infrastructure/crypto/DefaultHashPort.kt` | HashPort 适配器实现 |
| `domain/model/ConflictResolution.kt` | 冲突解决策略域模型 |
| `infrastructure/crypto/PemUtils.kt` | PEM/DER 转换共享工具 |
| `domain/service/ConflictResolver.kt` | 冲突解决器（重命名自 ConfilctResolver） |

## 四、删除文件

| 文件 | 原因 |
|------|------|
| `domain/service/ConfilctResolver.kt` | 拼写错误，已重命名 |
| `platform/tsl/` 目录 | 拼写错误，已重命名为 `tls/` |

## 五、验证状态

- ✅ 域层零 infrastructure import
- ✅ 域层零 Napier import（DebouncedFileWatcher）
- ✅ 所有 `PlatformFile` import 更新为 `domain.port`
- ✅ 所有 `ConflictResolution` import 更新为 `domain.model`
- ✅ 无残留 `QueryResult<Long>` 在域层
- ✅ 基础设施层 SqlDelight 仓储实现（`SqlDelightDeviceRepo`、`SqlDelightFileIndexRepo`、`SqlDelightPairingRequestRepo`、`SqlDelightTransferRepo`）完全兼容 `Long` 返回类型
- ✅ Android debug APK 编译成功 (`./gradlew assembleDebug` 成功通过)
- ✅ iOS 目标编译成功 (`./gradlew :sharedLogic:compileKotlinIosArm64` 成功通过，修复了 Kotlin 2.3.21/2.3.x 升级引入的 `NSFileHandle` 错误安全方法参数转换、UIKit 电池状态枚举、分类扩展属性显式导入、指针内存安全 Pin 传递等原生平台兼容性问题)
- ✅ 单元测试执行成功 (`./gradlew :sharedLogic:testAndroidHostTest` 成功通过)
