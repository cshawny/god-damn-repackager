# AGENTS.md — AI 助手操作手册

> **面向**: AI 编码助手(Claude、Copilot 等)和未来的你。
> **互补文件**: [TECHNICAL.md](TECHNICAL.md) 记录架构、原理和开发陷阱;**本文件**记录"在这个项目里该怎么干活"的规则与禁区。

---

## 1. 项目一句话

一个 Forge 1.20.1 Mixin 模组，让 Create 6.0+ 的理包机(Repackager)并行处理同一保险库(Vault)的大批量合成订单。N 个理包机 ≈ N 倍合成速度。当前处于 **alpha (0.3.0)**。

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

- `build/libs/goddamnrepackager-0.3.0-alpha.jar`。
- alpha 后缀由 `gradle.properties` 的 `mod_is_alpha=true` 控制(给 `jar` 任务设 `archiveClassifier = 'alpha'`)。脱离 alpha 后改 `false` 即可去掉后缀。

### 验证方式

- **AI 无法自己跑游戏。** 任何行为改动(class 文件变更)必须交给用户测试。提案里要写清楚"验证方案"，包含具体测试步骤。
- 编译通过(`BUILD SUCCESSFUL`)只证明代码语法正确，**不证明逻辑正确**。Mixin 注入点可能编译通过但运行时注入到错误的目标(见 TECHNICAL.md §3.1/§3.3)。
- 如果用户反馈问题，优先用 **DEBUG 日志** 定位(`GodDamnRepackager.DEBUG_LOGGING = true` 重编)。日志标记:
  - `[GDR-DIST]`:快照分配
  - `[GDR-REBAL]` / `STALLED-DONOR`:动态再平衡 / 堵塞恢复
  - `SPLIT MISMATCH` / `REBALANCE MISMATCH`:**这些永远打印**(是真错误)，说明物品计数不对。

---

## 3. 代码结构与禁区

### 核心文件(唯一要改的)

```
src/main/java/com/github/goddamnrepackager/
├── GodDamnRepackager.java          # @Mod 主类，只有日志+DEBUG_LOGGING 开关
└── mixin/
    └── RepackagerBlockEntityMixin.java   # ★ 所有核心逻辑全在这里
```

### 当前注入点(mixin 里)

| 注入 | 方法 | 用途 |
|---|---|---|
| `@Redirect` | `RepackagerBlockEntity.attemptToRepackage` 的 `List.addAll` | **思路A 快照分配**:winner repack 后按负载把整批包切分到各兄弟的私有队列 |
| `@Inject(@At("HEAD"))` | `RepackagerBlockEntity.attemptToSend` | **思路B 动态再平衡**:每次 lazyTick 唤醒(每 0.5s)时做队列再平衡，两种模式(堵塞恢复优先 / 保守均衡) |

### 绝对不要碰的东西(除非你读透了对应小节)

| 禁区 | 原因 | 必读 |
|---|---|---|
| `heldBox` 字段的**赋值/清空** | 清空是被动的(下游 `PackagerItemHandler.extractItem` 抽走才清)，打断此协议会死锁，物品复制/丢失风险极高 | TECHNICAL.md §3.8 |
| 父类 `PackagerBlockEntity.tick()` 里 offset 67-132 的取件逻辑(`get(0)`/`count--`/`remove`) | 这是发送状态机的心脏，且 Repackager **不重写 tick**(和普通 Packager 共用)。拦截会误伤普通打包机，除非 `instanceof` 早退 | TECHNICAL.md §3.8、§4.1 路径② |
| `BigItemStack.count` 的语义 | `count` 是"该包裹要发几次"，不是"包裹有几个"。切分时**必须按 count 分**，不能按列表元素个数分 | TECHNICAL.md §3.5 |
| Mixin 配置注册 | FG6 移除了 `mixin {}` 块，必须靠 MixinGradle 插件 + `mixin {}` 顶层块注册，否则 Mixin 编译进 jar 但运行时不触发 | TECHNICAL.md §3.1 |

### 复用现有 helper(不要重新发明)

- `findAvailableSiblingRepackagers(self)` — flood-fill 整个保险库多方块，找到所有连同一 vault 的兄弟理包机
- `vaultIdentifierOf(r)` — 返回稳定的 `InventoryIdentifier`(vault 下是 `Bounds(BoundingBox)`)，**不要用 `IItemHandler ==`** 做身份匹配(能力实例代次敏感，§3.7)
- `pendingShipmentCount(r)` — 某台理包机队列里的待发包总数
- `isStalled(r)` — 检测下游堵塞(`heldBox非空 && animationTicks==0`)
- `sumAllPending(siblings)` / `transferPackagesFromTail(self, donor, receiver, toMove, allowHeadRemoval)` — 搬运工具

### 访问 Create 字段的约定

- `queuedExitingPackages`、`heldBox`、`animationTicks`、`animationInward`、`buttonCooldown` 全是 **public**，在 mixin 里直接 `r.heldBox` 访问，**不需要 `@Accessor`**。
- `targetInventory`(InvManipulationBehaviour)也是 public。`getIdentifiedInventory()` 是 public 方法，用 `.identifier()` 取稳定标识。

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
| 发送逻辑(快照/再平衡/堵塞恢复) | `TECHNICAL.md` §2.2(算法)+ §2.3(防丢)+ §6(排查表) |
| 新增/修复开发陷阱 | `TECHNICAL.md` §3(陷阱实录) |
| 改变用户可见行为 | `README.md`(工作原理/已知限制) + `PUBLISH_mmcmod.md`(纯文本) + `PUBLISH_curseforge.md`(英文) |
| 版本号变更 | `gradle.properties` 的 `mod_version` + 三个文档的版本标识 |
| 构建约定 | `TECHNICAL.md` §5(构建环境) |

### alpha jar 后缀规则

- 当前 `mod_is_alpha=true` → jar 名为 `goddamnrepackager-0.3.0-alpha.jar`。
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
- **§3.6**:sibling 发现靠 flood-fill(固定半径扫描不可靠)
- **§3.7**:能力实例代次不一致导致 `==` 失效(已修复,但理解原理能避开类似陷阱)
- **§3.8**:heldBox 被动清空协议(动发送状态机前必读)
- **§4.1**:思路B 两条路径的完整对比(路径①已实现,路径②未实现+根因)

TECHNICAL.md 的 §6(常见问题排查表)和 §5(构建复现)也是日常会用到的。
