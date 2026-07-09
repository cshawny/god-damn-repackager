# AGENTS.md — AI 助手操作手册

> **面向**: AI 编码助手(Claude、Copilot 等)和未来的你。
> **互补文件**: [TECHNICAL.md](TECHNICAL.md) 记录架构、原理和开发陷阱;**本文件**记录"在这个项目里该怎么干活"的规则与禁区。

---

## 1. 项目一句话

一个 Forge 1.20.1 Mixin 模组，让 Create 6.0+ 的理包机(Repackager)并行处理同一保险库(Vault)的大批量合成订单。N 个理包机 ≈ N 倍合成速度。当前处于 **alpha (0.4.0)**(共享包裹池架构)。

---

## 2. 构建与验证

### 必须用 `./g.sh`

- 仓库里提供了 `g.sh` 包装脚本，它会把 `JAVA_HOME` 钉到 **JDK 17**。
- **不要用 `./gradlew`**——系统默认 Java 可能是 21/22，会导致 `Unsupported class file major version 66` 错误。
- 常用命令：
  ```bash
  ./g.sh clean build      # 编译 + 打 jar
  ./g.sh runClient        # 启动 dev 环境游戏(含 Create + JEI)
  ```

### 产出的 jar

- `build/libs/goddamnrepackager-0.4.0-alpha.jar`。
- alpha 后缀由 `gradle.properties` 的 `mod_is_alpha=true` 控制(给 `jar` 任务设 `archiveClassifier = 'alpha'`)。脱离 alpha 后改 `false` 即可去掉后缀。

### 验证方式

- **AI 无法自己跑游戏。** 任何行为改动(class 文件变更)必须交给用户测试。提案里要写清楚"验证方案"，包含具体测试步骤。
- 编译通过(`BUILD SUCCESSFUL`)只证明代码语法正确，**不证明逻辑正确**。Mixin 注入点可能编译通过但运行时注入到错误的目标(见 TECHNICAL.md §3.1/§3.3/§3.9)。
- 如果用户反馈问题，优先用 **DEBUG 日志** 定位(`GodDamnRepackager.DEBUG_LOGGING = true` 重编)。日志标记:
  - `[GDR-POOL] deposited`:理包机整理后整批入共享池
  - `[GDR-POOL] fed`:空闲理包机从池里取 1 个包
  - `[GDR-POOL] drained & dropped`:vault 销毁/扩建时池爆出实体

---

## 3. 代码结构与禁区

### 核心文件

```
src/main/java/com/github/goddamnrepackager/
├── GodDamnRepackager.java          # @Mod 主类，只有日志+DEBUG_LOGGING 开关
├── SharedPackagePool.java          # ★ 世界级 SavedData：per-vault 共享包裹池(deposit/poll/migrateKey/drainAndDrop)
├── VaultIdentity.java              # 工具类：vaultBoundingBoxOf(r) 返回 vault 的 BoundingBox(两个 mixin 共用)
├── VaultBoxTracker.java            # 工具类：WeakHashMap 追踪 controller 的 last-known box(reshape 迁移用)
└── mixin/
    ├── RepackagerBlockEntityMixin.java   # @Redirect addAll → 入池
    ├── PackagerBlockEntityMixin.java     # @Inject tick HEAD → 空闲理包机从池 poll(注父类，instanceof 守卫，须 redstonePowered)
    ├── ConnectivityHandlerMixin.java     # @Inject splitMulti(public) → vault 销毁(break/wrench)时 drop 池
    └── ItemVaultBlockEntityMixin.java    # @Inject notifyMultiUpdated(public) → vault reshape 时迁移池 key
```

> **注意**：mixin 类里**不能有 public/static 普通方法**——mixin 方法会被合并进目标类，非 private 的 static 方法会被 Mixin transformer 拒绝（`checkMethodVisibility` 报 `non-private static method`）。共享 helper 必须放独立工具类（如 `VaultIdentity`），不能放 mixin 类里。

### 当前注入点(mixin 里，三个)

| 注入 | 方法 | 用途 |
|---|---|---|
| `@Redirect` | `RepackagerBlockEntity.attemptToRepackage` 的 `List.addAll` | **整批入池**:winner repack 后 deposit 进共享池(不入私有队列) |
| `@Inject(@At("HEAD"))` | `PackagerBlockEntity.tick`(注**父类**!) | **策略 A 灌队列**:空闲理包机每 tick 从池 poll 1 个包进私有队列。handler 内 `instanceof Repackager` 早退 |
| `@Inject(@At("HEAD"))` | `ConnectivityHandler.splitMulti`(public，覆盖 break/wrench) | **vault 销毁 drop 池**:drainAndDrop 该 vault 的池成实体 |
| `@Inject(@At("HEAD"))` | `ItemVaultBlockEntity.notifyMultiUpdated`(public) | **vault reshape 迁移池 key**:旧 BoundingBox → 新 BoundingBox(包不爆不丢) |

### 绝对不要碰的东西(除非你读透了对应小节)

| 禁区 | 原因 | 必读 |
|---|---|---|
| `heldBox` 字段的**赋值/清空** | 清空是被动的(下游 `PackagerItemHandler.extractItem` 抽走才清)，打断此协议会死锁，物品复制/丢失风险极高 | TECHNICAL.md §3.8 |
| 父类 `PackagerBlockEntity.tick()` 里 offset 67-132 的取件逻辑(`get(0)`/`count--`/`remove`) | 这是发送状态机的心脏，且 Repackager **不重写 tick**。0.4.0 只在 tick HEAD 灌队列(策略 A)，**不拦 get(0)**(那是策略 B，高危)。注 tick 必须 `@Mixin(PackagerBlockEntity.class)` + `instanceof` 守卫 | TECHNICAL.md §3.8、§3.9①、§4.1 |
| `BigItemStack.count` 的语义 | `count` 是"该包裹要发几次"，不是"包裹有几个"。pool.poll() 按 count 拆分(>1 只取 1 份)，drainAndDrop 按 count 全量 drop | TECHNICAL.md §3.5 |
| Mixin 配置注册 | FG6 移除了 `mixin {}` 块，必须靠 MixinGradle 插件 + `mixin {}` 顶层块注册，否则 Mixin 编译进 jar 但运行时不触发 | TECHNICAL.md §3.1 |
| `splitMulti` vs `splitMultiAndInvalidate` + `isController()` footgun | vault 销毁只能注 public `splitMulti`(break/wrench 覆盖)；reshape 另用 `notifyMultiUpdated` 迁移 key。private `splitMultiAndInvalidate` 因 SearchCache 是 package-private **无法注入**(Mixin 要求 handler 匹配完整参数列表)。teardown 后 `isController()` 谎报 true，必须用 `getControllerBE()` | TECHNICAL.md §3.9②③⑥ |
| tick HEAD 灌队列必须守 `redstonePowered` | vanilla tick() 本身无红石检查(靠 lazyTick GATE 1)，我们的 tick 注入直接灌队列会绕过红石门。加 `if (!self.redstonePowered) return;`(见 §3.9⑦) | TECHNICAL.md §3.9⑦ |

### 复用现有 helper(不要重新发明)

- `SharedPackagePool.get(server)` — 取世界级池实例(SavedData)
- `SharedPackagePool.deposit(vault, batch)` / `poll(vault)` / `pending(vault)` / `drainAndDrop(vault, level, pos)` — 池操作
- `VaultIdentity.vaultBoundingBoxOf(r)` — 返回理包机所连 vault 的 `BoundingBox`(从 `InventoryIdentifier.Bounds.bounds()` 取，**不要用 `IItemHandler ==`**，§3.7)。独立工具类(非 mixin)，两个 mixin 共用

### 访问 Create 字段的约定

- `queuedExitingPackages`、`heldBox`、`animationTicks`、`animationInward`、`buttonCooldown` 全是 **public**，在 mixin 里直接 `r.heldBox` 访问，**不需要 `@Accessor`**。
- `targetInventory`(InvManipulationBehaviour)也是 public。`getIdentifiedInventory()` 是 public 方法，用 `.identifier()` 取稳定标识。
- `ItemVaultBlockEntity.getWidth()`/`getHeight()`/`getMainConnectionAxis()`/`getControllerBE()` 都是 public，`ConnectivityHandlerMixin` 直接用。

---

## 4. Create 源码分析方法论

### 不要凭记忆猜 Create API

这个项目的许多决策靠反编译 Create 字节码做依据。分析流程:
1. 找到 gradle 缓存里的 jar:
   - slim jar(含 PackagerBlockEntity 等):`~/.gradle/caches/forge_gradle/deobf_dependencies/com/simibubi/create/create-1.20.1/6.0.8-289_mapped_official_1.20.1/...-slim.jar`
   - all jar(含 RepackagerBlockEntity 等):同上目录 `...-291_...-all.jar`(版本号可能更新)
2. 用 `javap` 直接读 jar(不需解压):
   ```bash
   "/c/Program Files/Microsoft/jdk-17.0.11.9-hotspot/bin/javap" -p -c -classpath "JAR路径" 全限定类名
   ```
3. **不要用 `unzip -o`**(plan mode 拦截写操作)。解压用全新临时目录:`mkdir -p /tmp/xxx && cd /tmp/xxx && unzip -q JAR 'path/*.class'`。
4. 分析方法先看字段(`-p` 列出修饰符和类型)，再看关键方法字节码(`-c`)。找 `getfield`/`putfield` 定位字段使用、`invokevirtual/invokeinterface` 定位调用链。
5. **修改前必须先确认注入点的 `ordinal`**(同方法里同名调用的序号)。用 `javap -c` 输出数 `invokeinterface List.addAll` 出现次数，确认是第 0 个。

### 关键类的位置

| 类 | 所在 jar | 用途 |
|---|---|---|
| `RepackagerBlockEntity` | all jar | 理包机，重写了 `attemptToSend`/`attemptToRepackage`，**不重写 tick** |
| `PackagerBlockEntity` | slim jar | 父类，`tick()`/发送状态机/`heldBox`/`queuedExitingPackages` 都在这里 |
| `PackagerItemHandler` | slim jar | heldBox 的唯一清空点(`extractItem` → `setStackInSlot`) |
| `InvManipulationBehaviour` | slim jar | 理包机的 `targetInventory`，`getIdentifiedInventory()` |
| `ItemVaultBlockEntity` | all jar | 保险库，`initCapability()`/`getInvId()` |
| `InventoryIdentifier` | all jar | 稳定库存标识，`Bounds(BoundingBox)` 用于 vault |

---

## 5. 文档约定

### 改动后必须同步更新的文件

| 改了什么 | 更新文件 |
|---|---|
| 发送逻辑(共享池入池/取包/vault drop) | `TECHNICAL.md` §2.2(算法)+ §2.3(防丢)+ §6(排查表) |
| 新增/修复开发陷阱 | `TECHNICAL.md` §3(陷阱实录) |
| 改变用户可见行为 | `README.md`(工作原理/已知限制) + `PUBLISH_mmcmod.md`(纯文本) + `PUBLISH_curseforge.md`(英文) |
| 版本号变更 | `gradle.properties` 的 `mod_version` + 三个文档的版本标识 |
| 构建约定 | `TECHNICAL.md` §5(构建环境) |

### alpha jar 后缀规则

- 当前 `mod_is_alpha=true` → jar 名为 `goddamnrepackager-0.4.0-alpha.jar`。
- 文档里 jar 文件名示例要匹配(README 的安装步骤)。
- TECHNICAL.md §5 记录了此约定。
- 脱离 alpha 时:改 `gradle.properties` 的 `mod_is_alpha=false` + 去掉文档里 `-alpha` 后缀。

### 版本升级时

- **`PUBLISH_mmcmod.md`** / **`PUBLISH_curseforge.md`** 如果从"当前版本"改到新版本,要把旧版本的"历史修复说明"(如 0.2.0→0.2.1 的"需重新放置"修复)保留为历史语境(已标注 ~~删除线~~ 和修复版本号),不要误删——老用户需要这些信息。
- **README 的 ALPHA 警告区**和"安装"步骤里的版本号/jar 名要同步更新。

---

## 6. 进一步背景

以下 TECHNICAL.md 小节是**每次改动前应至少速览的**(它们记录了真实踩过的坑):

- **§3.1**:MixinGradle 配置(忘了的话 Mixin 不会触发)
- **§3.5**:`BigItemStack.count` 才是包裹数(按元素数切分是错的)
- **§3.7**:能力实例代次不一致导致 `==` 失效(已修复,但理解原理能避开类似陷阱)
- **§3.8**:heldBox 被动清空协议(动发送状态机前必读)
- **§3.9**:0.4.0 共享池的七个核心陷阱(tick 注父类 / splitMulti 可见性 / reshape 用 notifyMultiUpdated 迁移 / isController footgun / vault-centric 归属 / mixin 类不能有 public static 方法 / tick HEAD 必须守 redstonePowered)
- **§4.1**:思路B 两条路径(路径①0.3.0 实现已废弃,路径②0.4.0 共享池已实现;策略 A vs B 取舍)

TECHNICAL.md 的 §6(常见问题排查表)和 §5(构建复现)也是日常会用到的。
