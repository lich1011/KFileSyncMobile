# KFileSyncMobile 全代码审查报告

> **审查日期**: 2026-06-06
> **审查范围**: 全项目（`androidApp/`, `sharedLogic/`, `sharedUI/`）
> **代码量**: ~120 Kotlin 源文件，~15,000 行代码
> **技术栈**: Kotlin Multiplatform (Android + iOS), Compose Multiplatform, Ktor, SQLDelight, Koin

---

## 目录

1. [整体评价](#1-整体评价)
2. [架构违规 — 致命级](#2-架构违规--致命级)
3. [领域纯度问题](#3-领域纯度问题)
4. [代码重复](#4-代码重复)
5. [测试覆盖缺失](#5-测试覆盖缺失)
6. [安全性相关问题](#6-安全性相关问题)
7. [并发与线程安全](#7-并发与线程安全)
8. [命名与拼写错误](#8-命名与拼写错误)
9. [构建配置问题](#9-构建配置问题)
10. [UI/ViewModel 层问题](#10-uiviewmodel-层问题)
11. [设计与架构建议](#11-设计与架构建议)
12. [问题总览表](#12-问题总览表)

---

## 1. 整体评价

项目整体架构采用 **DDD + 六边形架构**，分层（Domain → Application → Infrastructure → Interfaces）明确，设计文档与代码高度对齐。值得肯定的方面包括：

- ✅ **领域模型设计扎实**: `Device`, `PairingSession`, `TransferJob`, `Share`, `FileEntry` 等聚合根有清晰的状态机和不变量约束
- ✅ **值对象使用 `value class`**: `DeviceId`, `Fingerprint`, `ShareId` 等通过 inline class 保证类型安全
- ✅ **事件总线架构成熟**: `SharedFlowEventBus` + 领域事件（past tense 命名）设计规范
- ✅ **错误层次清晰**: `DomainError` / `InfraError` / `AppError` 三层分离
- ✅ **安全设计完善**: TLS 证书钉扎、反重放窗口（`NonceWindow`）、PEM 指纹校验
- ✅ **同步策略模式**: `SyncPolicy` 策略模式可扩展，`VersionVector` 实现简洁正确

但存在以下 **需要重点关注的问题**：

---

## 2. 架构违规 — 致命级

> [!CAUTION]
> 以下违反了项目自身的 DDD 六边形架构规则："domain 层有 0 外部依赖，不得 import infrastructure/ 或 interfaces/"

### 2.1 Domain Service `Indexer` 直接依赖 Infrastructure

`Indexer.kt` 存在以下违规 import：

```diff
- import com.kfilesync.mobile.infrastructure.crypto.Blake3Hasher
- import com.kfilesync.mobile.infrastructure.crypto.StreamingSha256
- import com.kfilesync.mobile.infrastructure.crypto.hexLower
- import com.kfilesync.mobile.infrastructure.crypto.toHexLower
```

**影响**: `Indexer` 是核心 domain service，但直接耦合了 `infrastructure.crypto` 的具体实现。这使得领域层无法独立于基础设施进行单元测试。

**建议**: 在 `domain/port/` 下新增 `HashService` 端口接口，将 `Blake3Hasher` 和 `StreamingSha256` 包装成 adapter。

### 2.2 Domain Port `FileIO.kt` 反向依赖 Application 层

`FileIO.kt`：

```diff
- import com.kfilesync.mobile.application.service.PlatformFile
```

**影响**: Domain port 反向依赖了 Application 层的 DTO，违反了依赖方向规则。

**建议**: 将 `PlatformFile` 移至 `domain/port/` 包下（它本质上是一个与端口相关的值对象）。

### 2.3 Domain Port `Repository.kt` 依赖 SQLDelight

`Repository.kt`：

```diff
- import app.cash.sqldelight.db.QueryResult
```

**影响**: 领域端口的返回类型使用了 `QueryResult<Long>`（SQLDelight 特有类型），将 domain layer 绑定到了具体的持久化技术。

**建议**: 将 `QueryResult<Long>` 替换为简单的 `Long` 或自定义的 `Result<Long>` 返回类型。

### 2.4 Domain Service `ConflictResolver` 反向依赖 Application 层

`ConflictResolver` 的 `resolve()` 方法参数类型是 `application.service.ConflictResolution`（定义在 `SyncService.kt` 中的 sealed class）。这是经典的反向依赖。

**建议**: 将 `ConflictResolution` 枚举移至 `domain/model/` 或 `domain/service/` 下。

---

## 3. 领域纯度问题

### 3.1 `DeviceState` 状态机错误类型不一致

```kotlin
else -> Result.failure(IllegalStateException("Only Discovered can be paired (was $this)"))
else -> Result.failure(IllegalStateException("Only Paired can be revoked (was $this)"))
```

其他所有聚合根都使用 `DomainError.InvalidStateTransition`，但 `DeviceState` 使用了 `IllegalStateException`。这破坏了错误处理层的一致性。

### 3.2 `DebouncedFileWatcher` 属于 Domain Service 但依赖 Napier

```kotlin
import io.github.aakira.napier.Napier
```

Domain service 不应直接依赖日志库。应通过端口或完全不记录。

---

## 4. 代码重复

> [!WARNING]
> 以下代码存在显著重复，增加了维护负担和不一致风险。

### 4.1 `DevicePlatform.toWire()` 重复 2 处

| 位置 | 文件 |
|------|------|
| 1 | `PairingService.kt` |
| 2 | `HttpServer.kt` |

**建议**: 移至 `DevicePlatform` 枚举自身的扩展函数，放在 domain 模型文件中。

### 4.2 `pemToDer()` 重复 3 处

| 位置 | 文件 |
|------|------|
| 1 | `HttpServer.kt` |
| 2 | `PinnedTrustSnapshot.kt` |
| 3 | `IosSecIdentityBridge.kt` |

**建议**: 抽取为 `infrastructure.crypto` 包中的公共 utility 函数。

### 4.3 `base64Decode()` 重复 2 处

`PinnedTrustSnapshot.kt` 中有一个完整的 `base64Decode()` 私有实现，但 `Base64.kt` 中已有公共版本。

### 4.4 `platformFromWire()` / `toWire()` 重复

`PairingService.kt` 中同时存在 `DevicePlatform.toWire()` 和 `platformFromWire()` 的 file-private 版本，但 `SharePermission` 和 `SyncMode` 已将 `toWire()` / `fromWire()` 放在了枚举自身上。应统一风格。

---

## 5. 测试覆盖缺失

> [!CAUTION]
> 所有 3 个单元测试文件均为空壳，包含 0 个测试用例。

| 文件 | 状态 |
|------|------|
| `DeviceStateTest.kt` | ❌ 空类 |
| `VersionVectorTest.kt` | ❌ 空类 |
| `ChunkingStrategyTest.kt` | ❌ 空类 |

**存在 Fake 实现但无测试消费**:

| 文件 | 说明 |
|------|------|
| `FakeDeviceRepo.kt` | 未被任何测试引用 |
| `FakeEventBus.kt` | 未被任何测试引用 |
| `FakeKeyStore.kt` | 未被任何测试引用 |

**关键需测试的逻辑单元（按风险排序）**:

1. `VersionVector.isAncestorOf()` / `conflictsWith()` / `merge()` — 同步正确性的基石
2. `PairingSession.submitPin()` — PIN 验证 + 过期 + 最大尝试逻辑
3. `SyncPlanGenerator.generate()` — 6 路分类逻辑（正确性直接影响数据安全）
4. `ConflictResolver.conflictCopyName()` — 确定性命名（跨设备一致性依赖于此）
5. `IgnoreSpec.shouldIgnore()` — glob 匹配 + 否定规则优先级
6. `NonceWindow.verify()` — 反重放窗口边界条件
7. `SizeBasedChunking.computeChunkSize()` — 边界值
8. `TransferJob` 状态机完整图

---

## 6. 安全性相关问题

### 6.1 `SecurityHandler.onTrustRevoked()` 未完成级联清理

`transferService` 已经注入但未被使用。被吊销信任的设备仍然可能有进行中的传输任务未被取消。

### 6.2 `PinnedTrustSnapshot` + `SecurityHandler` 双重 `markPaired()` 调用

`PairingCompleted` 事件会同时触发两处 `trustBootstrapState.markPaired()`:
1. `SecurityHandler.kt`
2. `PinnedTrustSnapshot.kt`

虽然 `markPaired()` 是幂等的，但这属于职责不清 — 应该只在一处执行。

### 6.3 Release 构建未启用混淆

`androidApp/build.gradle.kts`：

```kotlin
getByName("release") {
    isMinifyEnabled = false
}
```

生产 APK 未做代码混淆 / 压缩。对于包含 TLS 证书管理和密钥存储逻辑的安全应用，这是个问题。

### 6.4 `ShareService.validateInvitedPermission()` 拒绝 `SendOnly` / `ReceiveOnly`

`ShareService.kt` 中 `validateInvitedPermission()` 的 `when` 分支只接受 `ReadOnly` 和 `ReadWrite`，其他（包括合法的 `SendOnly` / `ReceiveOnly`）都被降级为 `ReadOnly`：

```kotlin
return when (parsed) {
    SharePermission.ReadOnly, SharePermission.ReadWrite -> parsed
    else -> {
        Napier.w("unexpected permission '$wire' in /share/invite; downgrading to ReadOnly")
        SharePermission.ReadOnly
    }
}
```

如果桌面端发送 `send_only` 或 `receive_only` 权限的邀请，移动端会静默降级而不是报错。这可能导致用户困惑和功能丢失。

---

## 7. 并发与线程安全

### 7.1 `PlatformBackedLocalIdentityProvider` 使用 `runBlocking`

```kotlin
return runBlocking {
    mutex.withLock {
        ...
    }
}
```

虽然注释说"只会在首次调用时触发一次"，但 `runBlocking` 在主线程或协程内部调用时有死锁风险。如果 `DevicesViewModel.buildState()` 在协程中调用 `localIdentityProvider.current()`，而 `identityProvider.loadOrGenerate()` 需要跳到主线程完成 AndroidKeyStore 操作，就会形成死锁。

**建议**: 将 `LocalIdentityProvider.current()` 改为 `suspend fun`，或在 Application.onCreate 中强制预热。

### 7.2 `DebouncedFileWatcher` 潜在竞态

```kotlin
scope.launch {
    val freshJob = scope.launch {
        delay(debounceMillis)
        ...
    }
    lock.withLock {
        pendingByPath.remove(path)?.cancel()
        pendingByPath[path] = freshJob
    }
}
```

`freshJob` 在 `lock.withLock` 之前就已经通过 `scope.launch` 启动了。如果 `freshJob` 在 `lock.withLock` 获取锁之前就完成了 `delay` 和 `onChange` 调用，那么旧的 pending job 不会被取消，且新 job 的引用才刚被存入 map — 存在一个极小的时序窗口可能导致双重触发。

---

## 8. 命名与拼写错误

### 8.1 文件名拼写错误

| 当前名称 | 正确名称 | 文件路径 |
|----------|----------|----------|
| `ConfilctResolver.kt` | `ConflictResolver.kt` | `domain/service/ConfilctResolver.kt` |

### 8.2 `libs.versions.toml` 拼写错误

`libs.versions.toml` 中 `andoridx` 应为 `androidx`；注释中 `Andorid`/`uises`/`andoridMain` 应为 `Android`/`uses`/`androidMain`。

### 8.3 iOS TLS 目录命名

```
platform/tsl/   # ← 应为 tls/ (Transport Layer Security)
```

涉及文件:
- `IosPinningChallengeHandler.kt`
- `IosSecIdentityBridge.kt`
- `IosTlsListener.kt`

---

## 9. 构建配置问题

### 9.1 JVM Target 不一致

`sharedLogic` 使用 JVM 17 编译，但 `androidApp` 使用 JVM 11。这在 AGP 9 环境下可能不会立即报错，但可能导致隐含的不兼容问题。**建议统一为 JVM 17**。

### 9.2 `libs.versions.toml` 使用 beta 版本

生产项目依赖 alpha/beta 版本（如 `androidx-lifecycle`、`material3`、`voyager`）存在 API 变更和稳定性风险。

### 9.3 `androidx-lifecycle-process` 版本引用错误

使用了 `version =` 而不是 `version.ref =`。

---

## 10. UI/ViewModel 层问题

### 10.1 SettingsViewModel 直接引用实现类

ViewModel 不应直接引用 `SettingsServiceImpl.EMPTY`（实现类），应该引用接口 `SettingsAppService` 或将 `EMPTY` 提取为 companion 在接口上。

### 10.2 DevicesViewModel 中 `buildState()` 调用了同步 `current()`

`localIdentityProvider.current()` 是一个可能触发 `runBlocking` 的同步调用，在 `buildState()` 的 `combine` 流中每次状态变化都会执行。

---

## 11. 设计与架构建议

### 11.1 引入 Crypto Port

当前 `Blake3Hasher`, `StreamingSha256`, `HashProvider`, `SecureRng` 等密码学原语直接散落在 `infrastructure.crypto` 中，被 domain 和 application 层直接引用。建议抽象出领域层 Port。

### 11.2 引入 `Wire` 扩展对象

将所有 `toWire()` / `fromWire()` 方法统一放在 domain model 枚举的 companion 中。

### 11.3 Transfer Resume (Outgoing) 的用户体验

针对 Outgoing 方向的传输恢复直接返回失败，应当在 UI 层提供清晰的用户指引。

### 11.4 `SyncService.PUSH_AWAIT_TIMEOUT_MS` 应可配置

当前硬编码 10 分钟。建议通过 `SyncPolicy` 提供或至少通过 `companion object` 参数化。

---

## 12. 问题总览表

| # | 严重等级 | 类别 | 描述 | 位置 |
|---|---------|------|------|------|
| 1 | 🔴 致命 | 架构 | `Indexer` (domain) 直接 import `infrastructure.crypto` | `Indexer.kt` |
| 2 | 🔴 致命 | 架构 | `FileIO.kt` (domain port) 反向依赖 `application.service.PlatformFile` | `FileIO.kt` |
| 3 | 🔴 致命 | 架构 | `Repository.kt` (domain port) 使用 `app.cash.sqldelight.db.QueryResult` | `Repository.kt` |
| 4 | 🔴 致命 | 架构 | `ConflictResolver` (domain) 反向依赖 `application.service.ConflictResolution` | `ConflictResolver.kt` |
| 5 | 🟠 严重 | 测试 | 3 个测试文件全部为空壳，0 个测试用例 | `commonTest/` |
| 6 | 🟠 严重 | 构建 | `androidx-lifecycle-process` 使用 `version =` 而非 `version.ref =` | `libs.versions.toml` |
| 7 | 🟠 严重 | 安全 | `SecurityHandler.onTrustRevoked()` 未取消被撤销设备的活跃传输 | `SecurityHandler.kt` |
| 8 | 🟠 严重 | 安全 | Release 构建 `isMinifyEnabled = false` | `androidApp/build.gradle.kts` |
| 9 | 🟡 中等 | 构建 | JVM target 不一致 (11 vs 17) | `build.gradle.kts` |
| 10 | 🟡 中等 | 重复 | `DevicePlatform.toWire()` 重复 2 处 | `PairingService`, `HttpServer` |
| 11 | 🟡 中等 | 重复 | `pemToDer()` 重复 3 处 | 多文件 |
| 12 | 🟡 中等 | 重复 | `PinnedTrustSnapshot` 内含重复 `base64Decode()` | `PinnedTrustSnapshot.kt` |
| 13 | 🟡 中等 | 一致性 | `DeviceState` 错误类型使用 `IllegalStateException` 而非 `DomainError` | `DeviceState.kt` |
| 14 | 🟡 中等 | 安全 | `SecurityHandler` 和 `PinnedTrustSnapshot` 双重 `markPaired()` | 两文件 |
| 15 | 🟡 中等 | 逻辑 | `validateInvitedPermission()` 拒绝合法的 `SendOnly` / `ReceiveOnly` | `ShareService.kt` |
| 16 | 🟡 中等 | 并发 | `PlatformBackedLocalIdentityProvider` 使用 `runBlocking` | `LocalIdentity.kt` |
| 17 | 🟢 轻微 | 命名 | 文件名 `ConfilctResolver.kt` 拼写错误 | domain/service/ |
| 18 | 🟢 轻微 | 命名 | `libs.versions.toml` 中 `andoridx` 拼写错误 | `libs.versions.toml` |
| 19 | 🟢 轻微 | 命名 | iOS TLS 目录命名为 `tsl/` 而非 `tls/` | iosMain/platform/ |
| 20 | 🟢 轻微 | 命名 | 注释中 `Andorid`, `uises` 拼写错误 | `libs.versions.toml` |
| 21 | 🟢 轻微 | 依赖 | 生产项目使用 alpha/beta 版本库 | `libs.versions.toml` |
| 22 | 🟢 轻微 | 耦合 | `SettingsViewModel` 直接引用 `SettingsServiceImpl.EMPTY` | `SettingsViewModel.kt` |
| 23 | 🟢 轻微 | 领域 | `DebouncedFileWatcher` domain service 依赖 Napier | `DebouncedFileWatcher.kt` |

---

> **总结**: 项目在领域建模、安全设计、事件驱动架构方面做得相当好。最高优先级的修复应集中在 **4 个架构违规**（将领域层与 infrastructure/application 的耦合解除）和 **测试覆盖**（至少为 VersionVector、PairingSession、SyncPlanGenerator 补充基础测试）。构建配置中的版本引用错误（`version =` vs `version.ref =`）应立即修复，因为它可能导致编译失败或运行时异常。
