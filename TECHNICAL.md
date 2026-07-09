# God Damn Repackager — 技术文档

本文档记录模组的技术架构、实现方式、开发过程中遇到的关键陷阱，以及后续可能的演进方向。
面向读者：想接手开发的贡献者、想学习"如何用 Mixin 修改 Create 模组行为"的开发者。

---

## 1. 问题背景：Create 6.0 的理包机瓶颈

### 1.1 物流链路

Create 6.0 引入了全新的"包裹物流系统"。一个典型的自动化合成阵列长这样：

```
[总仓库]
   │  (玩家通过 Stockkeeper 下合成订单)
   ▼
蛙港(Frogport) ──把原料打成"碎片包裹"──> [输入保险库 Vault]
                                            │
                                  ┌─────────┼─────────┐
                                  ▼         ▼         ▼
                              理包机A    理包机B    理包机C   (Repackager, 红石块常开)
                                  │         │         │
                              整理成"一次合成所需的有序包裹"
                                  │         │         │
                              打包机(拆包) → 动力合成器 → 产物
```

理包机（`RepackagerBlockEntity`）的职责：**从保险库抽出碎片包裹，按合成配方把它们重新
整理成"正好一份合成所需原料"的有序包裹**（例如合成箱子 = 8 个木板一个包裹），然后逐个发出。

### 1.2 瓶颈在哪

原版行为：**整张订单的碎片被"抢到"它的那一个理包机独占**，它一次性把整张订单 repackage 成
几十/几百个有序包裹，全部塞进自己的 `queuedExitingPackages` 队列，然后**每秒只发出 1 个**
（发送动画周期 `CYCLE = 20` ticks = 1 秒）。

所以：1000 个合成的订单 = 1000 秒，无论你挂了多少理包机。

> **关键澄清**：瓶颈不在"抢碎片"（碎片进入保险库后几乎瞬间被某个理包机抽走），而在
> **"一个理包机慢慢消化它的输出队列"**。这个结论是本项目最费功夫得到的——下文详述诊断过程。

---

## 2. 模组架构

整个模组只有一个 Mixin 类 + 一个主类，极其精简：

```
src/main/java/com/github/goddamnrepackager/
├── GodDamnRepackager.java          # @Mod 主类，几乎只做日志 + DEBUG 开关
└── mixin/
    └── RepackagerBlockEntityMixin.java   # 唯一的核心逻辑

src/main/resources/
├── goddamnrepackager.mixins.json   # Mixin 配置
└── META-INF/mods.toml              # Forge mod 元数据
```

### 2.1 注入点

0.4.0 用四个注入点实现共享包裹池架构：

| 注入 | 方法 | 用途 |
|---|---|---|
| `@Redirect` | `RepackagerBlockEntity.attemptToRepackage` 的 `List.addAll` | 整批入池：winner repack 后不再塞进自己队列，而是 deposit 进共享池 |
| `@Inject(@At("HEAD"))` | `PackagerBlockEntity.tick`（注父类，因为 Repackager 不重写 tick） | 策略 A：空闲理包机每 tick 从池里 poll 1 个包灌进自己私有队列（须 `redstonePowered`） |
| `@Inject(@At("HEAD"))` | `ConnectivityHandler.splitMulti`（public，覆盖 break/wrench） | vault 销毁时 drop 池：把该 vault 的共享池清空并爆出实体 |
| `@Inject(@At("HEAD"))` | `ItemVaultBlockEntity.notifyMultiUpdated`（public） | vault reshape 时迁移池 key：把旧 BoundingBox 的池迁到新 BoundingBox |

> 为什么 tick 注入点在父类 `PackagerBlockEntity` 而非 `RepackagerBlockEntity`：
> Repackager **不重写** `tick()`（字节码已确认，见 §3.9）。Mixin 解析目标方法时按目标类自身
> 的方法表查找——Repackager 的方法表里没有 `tick`，所以 `@Mixin(Repackager.class) @Inject(method="tick")`
> **无法注入**。唯一可行模式是注父类 `PackagerBlockEntity` + handler 内 `instanceof Repackager`
> 早退（避免误伤普通打包机）。

### 2.2 核心算法：共享包裹池（0.4.0 架构）

0.3.0 的"快照分配 + 动态再平衡"两层机制已被 **共享包裹池** 架构彻底取代。核心数据结构是
`SharedPackagePool`——一个世界级 `SavedData`，按保险库的 `BoundingBox` 为 key 存一个
`Deque<BigItemStack>`。整个并行流程变成两步：

```
winner repack 整批
   └─→ 共享池 (SavedData, 按 vault BoundingBox 分桶)        ← 整批入池(@Redirect addAll)
         ↑
         └─→ 每 tick,空闲理包机 poll 1 个 → 私有队列          ← 按需取(@Inject tick HEAD)
               └─→ heldBox → 发送动画 → 下游 extractItem 清空  ← vanilla tick/extractItem,不动
```

**① 入池（@Redirect addAll）**

`attemptToRepackage` 末尾原版的 `queuedExitingPackages.addAll(boxesToExport)` 被 redirect 到
`SharedPackagePool.deposit(vaultKey, batch)`。winner 整批**不进任何私有队列**，全存入共享池。
winner 自己也只是个普通消费者——下个 tick 和兄弟平等地 poll。vault key 用
`VaultIdentity.vaultBoundingBoxOf(self)`：从 `targetInventory.getIdentifiedInventory().identifier()` 取
`InventoryIdentifier.Bounds.bounds()`（见 §3.7 的稳定标识原理）。

**② 按需取（@Inject tick HEAD，策略 A）**

这是关键的安全设计——**不碰 heldBox 生命周期**（§3.8）。每次 `tick()` 开始前先跑我们的注入：

```java
if (!(self instanceof RepackagerBlockEntity)) return;  // 不碰普通打包机
if (animationTicks != 0) return;        // 动画中,不打扰
if (!heldBox.isEmpty()) return;          // heldBox 还在(下游没抽走) → 堵塞,不取新包
if (!queuedExitingPackages.isEmpty()) return;  // 私有队列还有残留,先消化
pkg = pool.poll(vaultKey);               // 从池取 1 个(队首)
if (pkg != null) queuedExitingPackages.add(pkg);  // 灌进私有队列尾部
// ↓ vanilla tick() 紧接着从队首取它:
//   heldBox = queue[0]; animationTicks = 20; ...
```

**为什么安全**：注入只在队列空、heldBox 空、无动画时往队列尾部加 1 个包，vanilla tick 紧接着从
队首取它。私有队列始终 0~1 个元素，vanilla 取件逻辑看不到任何差异。heldBox 的被动清空协议
（§3.8）一行没动——零死锁风险。堵塞机（`heldBox` 非空）被 `!heldBox.isEmpty()` 守卫天然挡住，
不取新包，活自动流向空闲兄弟——**共享池天然就是动态均衡，不需要额外的再平衡逻辑**。

**③ count 语义（§3.5）**

`BigItemStack.count` 是"该包裹要发几次"，不是"包裹有几个"。池里的 `poll()` 按此语义拆分：若队首
`count > 1`，只拆出 1 份（`new BigItemStack(stack.copy(), 1)`），原 head 留在队首 `count--`。
这样池里的 `BigItemStack` 逐次被消费完才移除，和 vanilla 队列的 count 语义一致。

### 2.3 防物品复制/丢失

- **不复制**：整批 deposit 后只存在池里一份；poll 每次只拆出 1 份，count 严格递减
- **不丢失**：共享池是 `SavedData`，区块卸载/重进存档都保留（不随 BlockEntity 销毁）
- **vault 销毁/reshape 不吞包**：`ConnectivityHandlerMixin` 在 vault 销毁时 drainAndDrop，
  池里的包全爆成实体（见 §3.9 的 vault-centric 归属模型）
- **不干扰原版碎片清理**：redirect 的 addAll 在原版清空保险库碎片逻辑之后执行，碎片逻辑不动

---

## 3. 开发陷阱实录

这一节记录踩过的坑，按"代价大小"排序。**每一个都是真实踩过、耗费多轮迭代的坑。**

### 3.1 【最痛】ForgeGradle 6 的 Mixin 配置注册

**症状**：Mixin 类编译进了 jar，但运行时 `@Inject` 从不触发，且**没有任何报错**。
探针日志一行都没有，但主类的构造函数日志正常打印。

**根因**：ForgeGradle 6 的 `UserDevExtension` **移除了** `mixin {}` 配置块。
在 FG5 / 旧版本里，`minecraft { mixin { ... } }` 是注册自定义 mixin 配置的标准方式，
但 FG6 不支持。开发环境（runClient）下，Forge 从 sourceSet 加载 mod（不读 jar manifest 的
`MixinConfigs` 属性），所以我们的 mixin 配置根本没被注册。

**诊断关键**：run.log 里 `Remapping refMap ...` 列表只出现 flywheel/create/ponder 的 refmap，
**没有 `goddamnrepackager.refmap.json`**。这是 mixin 配置没被加载的铁证。

**错误尝试**（都失败了）：
- `property 'mixin.config', 'xxx.mixins.json'`（run config 里）→ 无效
- `arg '-mixin.config', 'xxx.mixins.json'`（run config 里）→ `arg()` 方法不存在
- 项目顶层 `mixin {}` 块 → `Could not find method mixin()`
- `minecraft { mixin {} }` → `UserDevExtension` 无此方法

**正确解法**：引入 **MixinGradle 插件**（`org.spongepowered.mixin`）：

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.spongepowered.mixin' version '0.7-SNAPSHOT'   // ← 关键
}

mixin {
    add sourceSets.main, "${mod_id}.refmap.json"
    config "${mod_id}.mixins.json"
}
```

MixinGradle 在项目顶层重新引入 `mixin {}` 扩展，负责生成 refmap + 把配置接入 dev 环境。
**这是 FG6 + Mixin 的唯一可靠方式。**

### 3.2 【次痛】`refmap` 重复导致 jar 打包失败

**症状**：`Entry goddamnrepackager.refmap.json is a duplicate`，`:jar` 任务失败。

**根因**：MixinGradle 的 `addMixinsToJar` 任务已经把 refmap 注入 jar；但我之前手写的
`copyRefmap` 任务又往 `build/resources/main/` 塞了一份，两份冲突。

**解法**：删掉手写的 copyRefmap 任务——MixinGradle 自己会处理 refmap 接入。
**教训**：加 MixinGradle 后，不要再用任何手动的 refmap 复制逻辑。

> 注意：删除任务定义后，残留的 refmap 文件还在 `build/resources/main/`，需要
> `clean build` 才能彻底清掉。普通 `build` 会因为缓存继续报错。

### 3.3 【诊断转折】"瓶颈在订单派发"是错误假设

**症状**：最初写的探针拦截 `LogisticsManager.broadcastPackageRequest`（总仓库的订单派发），
但探针从不触发，且游戏崩溃（`InvalidInjectionException`——参数类型 `Object` 不匹配实际的
`IdentifiedInventory`）。

**根因**：我把瓶颈定位错了。`broadcastPackageRequest` 是**总仓库**把订单派给打包机的逻辑，
和玩家合成阵列里的理包机**毫无关系**。理包机是红石常开、自主轮询的，根本不经过订单派发。

**教训**：修改一个模组前，必须用**探针实测**确认"你以为的瓶颈代码路径"真的是瓶颈。
本项目光是诊断瓶颈位置就花了 5+ 轮迭代（从"订单派发" → "碎片竞争" → "碎片死锁" →
最终定位到"输出队列独占"）。每一步都是探针数据推翻了上一步的假设。

### 3.4 【术语混淆】Packager vs Repackager

Create 里有两个相似但不同的方块：
- `PackagerBlockEntity`（打包机）：把容器物品打包成包裹 / 拆包
- `RepackagerBlockEntity extends PackagerBlockEntity`（理包机）：合并碎片包裹成有序合成包裹

开发全程我把这俩的中文译名搞反了多次，导致沟通混乱。**源码铁律**：`Repackager` 继承自
`Packager`，但**重写了** `attemptToSend` 和 `redstoneModeActive`，且**不参与链接站逻辑**
（`getPackager()` 显式排除它）。

### 3.5 【数值理解】`BigItemStack.count` 才是真正的包裹数

**症状**：第一版分配逻辑按"列表元素个数"切分 `boxesToExport`，但日志显示
`distributing 1 packages`——明明是 60 个合成，怎么会只有 1 个包裹？

**根因**：`repackBasedOnRecipes` 对每个配方只生成**一个** `BigItemStack`，它的 `count`
字段才代表"这个包裹要发多少次"。60 个合成 = 1 个 `BigItemStack(box, count=60)`，
发送逻辑（`tick()` L143-149）每次 `entry.count--`，归零才移除。

**解法**：分配时按 `BigItemStack.count` 切分（`new BigItemStack(stack.copy(), share)`），
而不是按列表元素。

### 3.6 【sibling 发现】固定半径扫描不可靠

**症状**：9 个理包机（3 面各 3 个）的保险库，只能找到 3~5 个兄弟。

**根因**：早期用"winner 周围半径 2 的立方体"扫描，但大保险库对角的理包机距离 >2。

**解法**：flood-fill 整个保险库多方块结构（见 §2.2①）。`InventoryIdentifier` 值相等作为
正确性保障——即使扫到别的理包机，连的不是同一个保险库也会被过滤（0.2.0 曾用 `IItemHandler ==`，
因能力实例代次问题不可靠，0.2.1 改为值相等，见 §3.7）。

### 3.7 能力实例身份匹配对缓存"代次"敏感（0.2.0 问题，0.2.1 已修复）

**症状**：在**已存在的存档里首次安装本模组 0.2.0**（尤其服务器多人存档）后，一个保险库周围贴了
9 个理包机，下一张订单却只有 6 个工作；而同样的阵列设计在**新存档/单人生存**里完全正常。
把理包机拆掉**重新放一遍**就恢复正常。

**根因**：0.2.0 的 sibling 校验用 `IItemHandler` 的 `==` 身份匹配（`RepackagerBlockEntityMixin`
里的 `sibInv != myInv`）。这个 `IItemHandler` 不是凭空冒出来的——它是 `InvManipulationBehaviour.getInventory()`
返回的 `targetCapability.orElse(null)`，而 `targetCapability` 是 forge 的 `LazyOptional` 缓存。

保险库（`ItemVaultBlockEntity.initCapability`）的 controller 块**首次被查询能力时**才构造出
`VersionedInventoryWrapper` 包 `CombinedInvWrapper`，非 controller 块把自己指向 controller 的同一个
`LazyOptional`。这个 `LazyOptional` **不是永生的**：保险库多方块结构在 chunk 卸载/重载、controller
切换、capability invalidate 时会重建，重建后对外暴露的就是一个**全新的 `IItemHandler` 实例**。
Create 的 `CapManipulationBehaviourBase` 自己也有失效重建路径（`onHandlerInvalidated` 清缓存 +
置 `findNewNextTick`，下个 `tick()` 调 `findNewCapability()` 重建）。

所以身份匹配成立有一个**隐含前提：所有理包机的 `targetCapability` 缓存都指向同一代保险库能力实例**。
在"老存档装新模组"时，各理包机当初是在没有本模组时放置的，`targetInventory` 的缓存在不同时刻、
面对不同代次的保险库能力实例建立；服务器上区块反复加载、tick 分片、跨区块交互比单人更频繁地触发
重建，于是不同理包机的缓存可能指向**不同代**的保险库能力实例——`sibInv != myInv`，兄弟被丢弃，
表现为"只有部分理包机工作"。

**为什么单人生存不受影响**：单人环境区块加载稳定、tick 顺序简单、极少触发保险库能力重建，
理包机几乎都在同一代能力实例内完成缓存，身份一致。

**为什么"重新放一遍就好"**：重新放置会**销毁并重建该理包机的 `InvManipulationBehaviour`**，
`targetCapability` 重新指向保险库**当前**那一代能力实例。一旦所有理包机对齐到同一代，`==` 就一致了。

**修复（0.2.1）**：改用 Create 自己的 `InventoryIdentifier` 值相等来判定同库，不再用 `IItemHandler ==`。
具体：通过 `InvManipulationBehaviour.getIdentifiedInventory().identifier()` 取标识，对 vault 它是
`InventoryIdentifier.Bounds(BoundingBox)`——一个 record，`equals` 只比多方块的两个角坐标（6 个 int），
**完全不碰能力实例**。所以无论保险库能力重建多少次、各理包机的 `targetCapability` 缓存指向哪一代，
只要保险库几何形状不变，`Bounds` 就相等，兄弟就不会被误判丢弃。

> 注意：要比的是 `IdentifiedInventory.identifier()`，**不是整个 `IdentifiedInventory`**——后者是 record，
> 但其 equals 同时包含 `IItemHandler` 字段，直接比两个 record 又会落回能力实例身份问题。

此修复后，老存档安装 0.2.1 **无需重新放置理包机**。改动只影响"哪些理包机参与分配"这一判定，
不触碰 count 切分、`BigItemStack` 分发、`SPLIT MISMATCH` 守卫——物品增减风险为零。

> **历史诊断（0.2.x 时代适用）**：0.3.0 用 `[GDR-DIST] ... across N repackager(s)` 的 N 排查 sibling
> 漏找。**0.4.0 共享池架构不再有 sibling 发现**（每机独立从池 poll），此诊断已不适用。若 0.4.0 下仍
> 有"部分理包机不工作"，查 `vaultBoundingBoxOf` 是否对这些机返回 null（targetInventory 未就绪）。

### 3.8 【关键背景】发送状态机依赖 heldBox 被动清空协议

**为什么记这一节**：设计思路 B（动态负载均衡）时，最初设想是拦截 `tick()` 的取件逻辑
（`queuedExitingPackages.get(0)`）。字节码调研发现这条路风险极高，根因就是本节记录的协议。
任何未来想动发送状态机的改动（包括 §4.2 的真·共享池）都必须先吃透这里。

**tick 取件条件**（`PackagerBlockEntity.tick()`，Repackager 不重写、继承父类）：

```
if (animationTicks == 0 && !level.isClientSide
    && !queuedExitingPackages.isEmpty() && heldBox.isEmpty()) {
    BigItemStack bis = queuedExitingPackages.get(0);
    heldBox = bis.stack.copy();
    bis.count--;
    if (bis.count <= 0) queuedExitingPackages.remove(0);
    animationInward = false;
    animationTicks = 20;   // CYCLE：1 包 / 20 tick（1 秒）
    notifyUpdate();
}
```

**heldBox 从不被理包机自己清空**。`PackagerBlockEntity` 全文中对 `heldBox` 的写入只有：构造时
`EMPTY`、tick 取件时 `copy()`、`attemptToSend` 直发时赋新包、NBT 读入。**动画结束（`animationTicks`
减到 0）那一拍只调 `wakeTheFrogs()` + `setChanged()`，不清 heldBox。**

**heldBox 的清空是被动的**——靠下游库存通过 `PackagerItemHandler.extractItem` 抽走它：

```
PackagerItemHandler.extractItem(slot, amount, simulate):
    if (animationTicks != 0) return EMPTY;        // 动画期间禁止抽
    ItemStack local = blockEntity.heldBox;
    if (!simulate) setStackInSlot(slot, EMPTY);   // ← 唯一的清空点
    return local;

setStackInSlot(slot, stack):
    if (slot != 0) return;
    blockEntity.heldBox = stack;                   // putfield heldBox
    blockEntity.notifyUpdate();
```

**含义**：vanilla 的"连续发包"完全依赖"下游库存会主动从理包机槽位抽走 heldBox"。一旦下游抽不动
（库存满、无下游），heldBox 卡住非空，`heldBox.isEmpty()` 永假，tick 取件死锁——但 vanilla 靠
extractItem 兜底，所以正常场景下不会发生。

**持续发包靠 lazyTick**（周期 10 tick ≈ 0.5 秒，`SmartBlockEntity` 默认 `setLazyTickRate(10)`）：
`lazyTick` 在红石常开模式下每 0.5 秒调一次 `attemptToSend(null)`。`activate()`（红石上升沿）只在
开机触发一次并设 `buttonCooldown=40`（2 秒退避），之后稳态发送频率 = lazyTick 周期。

**对思路 B 的影响**：

- **路径 ①（0.3.0 采用，0.4.0 已废弃）**：完全不动 tick / heldBox / extractItem。再平衡只在各机私有
  `queuedExitingPackages` 之间搬队尾元素，发送状态机照原样跑。零死锁风险。0.4.0 用共享池取代了它。
- **路径 ②（真·共享池，0.4.0 已采用）**：原 §4.1 估计拦 tick 取件要接管 heldBox 生命周期、风险高。
  0.4.0 用**策略 A（tick HEAD 灌队列）**规避了这个风险——不拦 tick 的 `get(0)` 取件行，而是在 tick
  HEAD 往私有队列尾部加包，vanilla tick 照常从队首取。heldBox 生命周期一行没动，零死锁。详见 §3.9、§4.1。

### 3.9 【0.4.0 核心陷阱】共享池脱离 BlockEntity → vault-centric 归属 + 三个 mixin 陷阱

0.4.0 把包从"各理包机私有 `queuedExitingPackages`"搬到"世界级 `SharedPackagePool`(SavedData)"。这个
架构转变带来七个必须吃透的陷阱，每一个都踩过/差点踩过。

**① tick 必须注父类 `PackagerBlockEntity`，不能注 `RepackagerBlockEntity`**

字节码确认 Repackager **不重写** `tick()`（继承父类）。Mixin 解析目标方法时按目标类自身的方法表查找，
Repackager 的方法表里没有 `tick`，所以 `@Mixin(Repackager.class) @Inject(method="tick")` **无法注入**
（annotation processor 会报 "Unable to locate target tick"）。唯一可行模式：

```java
@Mixin(value = PackagerBlockEntity.class, remap = false)  // 注父类
@Inject(method = "tick()V", at = @At("HEAD"))
private void feedFromPool(CallbackInfo ci) {
    if (!(((Object)this) instanceof RepackagerBlockEntity)) return;  // 早退,不误伤普通打包机
    ...
}
```

所有新 mixin 都要 `remap = false`：注入的是 Create/Minecraft 自身方法（official mapping 下名字就是
字面名），不需要 searge refmap 重映射。不加 `remap = false`，annotation processor 会报
"Unable to locate obfuscation mapping for @Inject target tick"。

**② vault 销毁钩子：注 public `splitMulti`，不能注 private `splitMultiAndInvalidate`**

vault 销毁/reshape 的通用 chokepoint 是 `ConnectivityHandler.splitMultiAndInvalidate`（ItemVaultBlock.
onRemove / onWrenched / tryToFormNewMulti 都走它），但它有两个可见性障碍使其**无法从 mixin 注入**：

- 方法是 `private static`。
- 第二个参数 `SearchCache<T>` 是 **package-private**（`class` 非 `public`）→ 我们的代码**无法 import** 它。

**踩过的坑**：初版试图"只捕获第一个 `BlockEntity` 参数、跳过 SearchCache"来绕过可见性。**这是错的**——
Mixin 的 `@Inject` handler **必须匹配目标方法的完整参数列表**（可省略尾部参数，但不能跳过中间的）。
运行时报 `InvalidInjectionException: Expected (BlockEntity;SearchCache;Z;CallbackInfo;)V but found
(BlockEntity;CallbackInfo;)V`，游戏进世界即崩。那条 "Cannot find target method" 的 AP **警告不是良性
的**——它反映的是 AP 无法验证 private+package-private 方法，但运行时 Mixin 一样无法注入。

**正确的解法**：改注 **public `splitMulti(T)`**（break/wrench 的公开入口，擦除后参数只有一个 BlockEntity，
handler 用 `(BlockEntity, CallbackInfo)` 即可）。它不覆盖 add-block reshape（reshape 走内部的
`splitMultiAndInvalidate`，不经 `splitMulti`）——reshape 由**第四个 mixin**（`ItemVaultBlockEntityMixin`
注 `notifyMultiUpdated`）单独处理，见 §3.9⑥。

**③ `isController()` 在 teardown 后是 footgun**

`isController()` 返回 `controller == null`。`removeController()` 会 null 掉 controller 字段，于是 teardown
后每个 part 都谎报自己是 controller。**绝不能用 `isController()` 做 teardown 时的 controller 判定**。
正确做法：用 `getControllerBE()`（在 HEAD 时 controller 字段还没被 null，返回真实 controller）。已在
`ConnectivityHandlerMixin` 里这么做。

**④ vault-centric 归属：打掉理包机不爆，打掉/扩建 vault 才爆**

共享池存在 SavedData 里，与 BlockEntity 独立。这导致一个根本行为变化（README/PUBLISH 已告知玩家）：

- **打掉理包机** → vanilla `destroy()` 只 drop `heldBox` + 私有 `queuedExitingPackages`（此时基本是空的，
  因为策略 A 下私有队列只 0~1 元素）。**共享池不爆**，包安全留 SavedData，重放理包机即恢复。
- **打掉 vault（任意方块）/ 扳手拆除** → `ConnectivityHandlerMixin`（注 public `splitMulti`）触发
  `drainAndDrop`，该 vault 的池全爆成实体。
- **扩建 vault（reshape，加/减方块但不全拆）** → `ItemVaultBlockEntityMixin`（注 `notifyMultiUpdated`）
  把池 key 从旧 BoundingBox **迁移**到新 BoundingBox，包不爆不丢，理包机继续从新 key 取（见 §3.9⑥）。

**重建 BoundingBox 必须用 `initCapability()` 的精确公式**，不能用 `getInvId()`（`invId` 字段在能力首次
查询前是 null）：vault 轴是 X 或 Z（不会是 Y），`box = fromCorners(pos, axis==Z ? pos.offset(r,r,l) : pos.offset(l,r,r))`，
其中 pos = controller 的 worldPosition，r = getWidth()，l = getHeight()。已在 `ConnectivityHandlerMixin` 里实现。

**⑤ mixin 类里不能有 public/static 普通方法（运行时崩溃）**

**症状**：游戏启动崩溃，`InvalidMixinException: Mixin ... contains non-private static method vaultBoundingBoxOf(...)`
，`checkMethodVisibility` 报错。

**根因**：mixin 类的方法会被**合并进目标类**。0.4.0 初版把 `vaultBoundingBoxOf` 声明成
`RepackagerBlockEntityMixin` 的 `public static` 方法，想让另一个 mixin（`PackagerBlockEntityMixin`）调用它。
但 Mixin transformer 拒绝把非 private 的 static 方法合并进目标类（会污染 `RepackagerBlockEntity` 的 API 表面）。

**修复**：共享 helper 必须放**独立工具类**（非 mixin），不能放 mixin 类里。`vaultBoundingBoxOf` 已搬到
`com.github.goddamnrepackager.VaultIdentity`（普通 Java 类），两个 mixin 都调用 `VaultIdentity.vaultBoundingBoxOf(r)`。
mixin 类里的 helper 方法必须是 `private`（实例方法），跨 mixin 共享的逻辑一律抽到独立类。

**⑥ reshape 迁移：`notifyMultiUpdated()` 是 reshape 的唯一 public 钩子**

`splitMulti` 不覆盖 reshape（reshape 走私有 `splitMultiAndInvalidate`，不经 `splitMulti`），所以扩建 vault
（3×3×3 → 3×3×4）时旧 BoundingBox key 会变孤儿——池里的包匹配不上新形状，理包机取不到（表现为"订单
卡住，拆回原形状又恢复"）。

**解法**：注 public `ItemVaultBlockEntity.notifyMultiUpdated()`（`()V`，无参，易注入）。字节码确认它在
reshape 路径（`tryToFormNewMulti`）里 `setWidth(newWidth)/setHeight(newLength)` **之后**调用（offset 202），
所以 HEAD 时 `getWidth()/getHeight()/getMainConnectionAxis()/getBlockPos()` 反映**新**几何。它也在 fresh
formation 时触发，所以用"last-known box 变化"判定 reshape：

- `VaultBoxTracker`（独立工具类，`WeakHashMap<ItemVaultBlockEntity, BoundingBox>`）记录每个 controller 的
  last-known box。WeakHashMap 在 BE 被 GC 时自动清理。
- `notifyMultiUpdated` HEAD：算 newBox = `boxOf(self)`；若 `lastBox(self)` 存在且 ≠ newBox，调
  `SharedPackagePool.migrateKey(oldBox, newBox)`（把 deque 原样搬到新 key，FIFO/count 不变，不 drop）；
  然后 `remember(self, newBox)` 更新基线。首次（无 oldBox）只 remember，不 migrate。
- teardown（`splitMulti` HEAD）时 `forget(vault)` 清掉追踪条目，避免陈旧基线。

**关键**：`notifyMultiUpdated` 在 fresh formation 时会对每个 part 也调用，但只有 controller 持有真实多方块
几何（`radius>1 || length>1`），part 的 box 是单方块位置——所以用 `isController()` + 几何大小守卫过滤。

**⑦ tick HEAD 灌队列必须守 redstone，否则理包机无需红石也能工作**

**症状**：理包机没有红石信号也在发包，违反机械动力原版设计。

**根因**：vanilla `tick()` **本身没有红石检查**——它只从 `queuedExitingPackages` 取包发包。vanilla 靠
`lazyTick()` 的 GATE 1（`if (!redstonePowered) return`）守住：只有红石充能时 `attemptToSend` 才会往队列
塞包。但我们的 `PackagerBlockEntityMixin.feedFromPool` 在 tick HEAD **直接往队列灌包**，绕过了 lazyTick 的
红石门，所以 tick 照常取包发包，无视红石。

**修复**：在 `feedFromPool` 顶部加 `if (!self.redstonePowered) return;`。`redstonePowered` 是
`PackagerBlockEntity` 的 public 字段（vanilla lazyTick GATE 1 读的就是它）。`redstoneModeActive()` 不是
正确的门——Repackager 把它 override 成恒 `true`（那是"模式选择器"，不是红石门）。deposit（redirect）
不受影响——它发生在已被 lazyTick 红石门守住的 `attemptToSend` 路径里。

---

## 4. 后续开发方向

### 4.1 动态负载均衡（思路 B）

**原思路 A 的局限**：0.3.0 的快照分配是"一次性快照"——在 repack 完成那一刻决定谁分多少，再靠
动态再平衡补静态性。两层逻辑各自有阈值、有 sibling 发现、有守恒校验，代码复杂。

思路 B 评估了两条路径，最终 0.4.0 实现了路径 ②（真·共享池）。

#### 路径 ①：动态再平衡（0.3.0 实现，0.4.0 已废弃）

**思路**：不动发送状态机，只在 `attemptToSend` 头部加一道再平衡——把"队列最深者"队尾的一小部分包
偷给"队列最浅者"队尾。空闲理包机就能动态吃到原本堆在别机队列里的活。

**为什么 0.3.0 选它**：完全不碰 `tick()` / `heldBox` / `extractItem`（§3.8 被动清空协议），零死锁风险。

**0.4.0 为什么废弃**：路径 ②（共享池）用更低复杂度达成了相同效果（天然动态均衡，无需再平衡层）。
路径 ① 的快照分配 + 保守阈值 + 堵塞恢复 + sibling flood-fill 全部删除，代码大幅精简。

#### 路径 ②：真·共享池（0.4.0 已实现）

**思路**：per-vault 全局 `SharedPackagePool`（SavedData）。winner repack 的整批**入共享池**，各理包机
`tick()` 时从池里 poll。

**0.4.0 的关键决策——策略 A（tick HEAD 灌队列）取代策略 B（拦 get(0)）**：

原 §4.1 设想的"拦 `tick()` 里 `queuedExitingPackages.get(0)`、池直接成取件源"（策略 B）需要接管
heldBox 生命周期（§3.8），死锁/复制风险高——这正是原版估 30-40% 成功率的根因。0.4.0 字节码调研
（§3.8 已补全）后发现，**只要取包点放在 tick HEAD 而非 get(0)**，就能完全不碰 heldBox 生命周期：

- **策略 A**：`@Inject` 挂 tick HEAD，空闲时往私有队列尾部 add 1 个包；vanilla tick 紧接着从队首取。
  私有队列 0~1 元素，heldBox 协议不动，**零死锁**。
- **策略 B**（原设想，未采用）：`@Redirect` 拦 get(0)，池成取件源；须接管 heldBox 生命周期。风险高。

两种策略玩家体感几乎无别（都是空闲机动态取活），但策略 A 风险低一个数量级。**偏离原 §4.1 字面描述
是刻意的**——字节码已摸透 heldBox 被动清空协议，没必要走高危路。

**0.4.0 还要解决的独有问题——vault-centric 归属（§3.9 详述）**：共享池脱离 BlockEntity 存在
（SavedData），所以"打掉理包机"不会爆池（池留着，重放即恢复），但"打掉/扩建 vault"必须把池爆出来，
否则包静默吞没。解法是第三个 mixin：`@Inject` `ConnectivityHandler.splitMultiAndInvalidate`，
在 vault 销毁/reshape 时 drainAndDrop。

**实现细节见 §2.2**。三个注入点（addAll redirect / tick inject / splitMultiAndInvalidate inject）
见 §2.1。

### 4.2 其它可能方向

- **配置文件**：让玩家可调"最少几个理包机才触发分配""是否启用本模组"。
  目前行为对所有人都合理，但配置能增加灵活性。
- **支持非 Vault 容器**：`IItemHandler` 身份匹配理论上对 Crate / 原版箱子同样成立，
  但 flood-fill 的 `BlockEntity` 判断逻辑可能要调整（Crate 不是 Vault）。需实测。
- **Fabric 移植**：Mixin 本体大部分可复用，但入口、注册、依赖坐标都要重写。
  Create 6.0 有 Fabric 版本，可作为目标。建议在 Forge 版彻底稳定后再做。
- **Create 版本跟进**：Create 更新后，`attemptToRepackage` 的行号 / `addAll` 的
  target 描述符可能变化。需要针对新版本重新验证注入点。

---

## 5. 开发环境复现

如需从源码构建：

1. 安装 **JDK 17**（必须是 17，不能是 21/22）
2. `git clone` 本仓库
3. 用仓库里的 `g.sh` 包装脚本运行 gradle（它会把 `JAVA_HOME` 钉到 JDK 17）：
   ```bash
   ./g.sh clean build      # 编译 + 打 jar
   ./g.sh runClient        # 启动开发环境游戏（含 Create + JEI）
   ```
4. 发布 jar 在 `build/libs/` 下。**alpha 阶段的 jar 带有 `-alpha` 后缀**，例如
   `goddamnrepackager-0.4.0-alpha.jar`（由 `gradle.properties` 里的 `mod_is_alpha=true` 控制，
   通过给 `jar` 任务设 `archiveClassifier = 'alpha'` 实现）。脱离 alpha 后把 `mod_is_alpha` 改为
   `false`，jar 名即变为 `goddamnrepackager-<version>.jar`。

> `g.sh` 是必须的——直接 `./gradlew` 会用系统默认 Java（可能是 22），导致
> `Unsupported class file major version 66` 错误。

### 关键依赖（见 `build.gradle`）

| 依赖 | 版本 | 用途 |
|---|---|---|
| Minecraft | 1.20.1 | 目标版本 |
| Forge | 47.2.0 | 加载器 |
| Create | 6.0.8-289 | 被修改的模组（用 `:slim` + 显式 Ponder/Flywheel/Registrate） |
| MixinGradle | 0.7-SNAPSHOT | Mixin 配置注册（FG6 必需，见 §3.1） |
| JEI | 15.20.0.106 | 仅 dev 环境用，用于下合成订单测试 |

---

## 6. 调试技巧

### 开启诊断日志

在 `GodDamnRepackager.java` 里把 `DEBUG_LOGGING = true` 重新编译。开启后：
- 每次入池打印 `[GDR-POOL] deposited N package(s)` 行：理包机整理后整批入池的总量。
- 每次空闲理包机取包打印 `[GDR-POOL] fed 1 package` 行：哪个理包机取了包、池里还剩多少。
- 每次 vault 销毁/reshape 爆池打印 `[GDR-POOL] drained & dropped N package(s)` 行。
- （0.3.0 的 `[GDR-DIST]` / `[GDR-REBAL]` / `SPLIT MISMATCH` / `REBALANCE MISMATCH` 已随快照+再平衡
  逻辑删除，0.4.0 不再出现。）

### 常见问题排查

| 现象 | 可能原因 |
|---|---|
| Mixin 不触发，无报错 | MixinGradle 没配好，refmap 没生成（见 §3.1） |
| `mixin class is invalid` | refmap 缺失或 target 描述符错（见 §3.1） |
| 进世界崩溃 `InvalidInjectionException`（Expected ...SearchCache... but found） | 0.4.0：注了 private `splitMultiAndInvalidate` 且 handler 跳过了 SearchCache 参数。Mixin 要求 handler 匹配目标完整参数列表。改注 public `splitMulti`（见 §3.9②） |
| 游戏启动崩溃 `non-private static method`（checkMethodVisibility） | 0.4.0：mixin 类里有 public/static 普通方法。共享 helper 必须放独立工具类（见 §3.9⑤） |
| 游戏启动崩溃（tick） | 0.4.0：tick 注入点写错（必须注父类 PackagerBlockEntity + instanceof 守卫，见 §3.9①） |
| 理包机无需红石也工作 | 0.4.0：tick HEAD 灌队列漏了红石守卫。加 `if (!self.redstonePowered) return;`（见 §3.9⑦） |
| 扩建 vault 后订单卡住（拆回又恢复） | 0.4.0：reshape 没迁移池 key，旧 BoundingBox 变孤儿。需注 `notifyMultiUpdated` 迁移（见 §3.9⑥） |
| 只有部分理包机工作 | 0.4.0 下应是历史问题：① 若用 0.2.0 升级到 0.2.1+（见 §3.7）。② 0.4.0 共享池架构不依赖 sibling 发现，每机独立 poll，不应再有此问题；若仍有，查 `vaultBoundingBoxOf` 是否返回 null（targetInventory 未就绪） |
| 产物数量不对 | 0.4.0：开 `DEBUG_LOGGING` 看 `[GDR-POOL] deposited` 总量是否 == 订单数，`fed` 累计是否 == deposited。共享池是纯搬运+count 拆分，不等说明 poll/deposit 逻辑错（见 §2.3） |
| 下游堵塞时积压不消散 | 确认 0.4.0+。堵塞机被 `!heldBox.isEmpty()` 守卫挡住不取新包，活自动流向空闲兄弟（共享池天然均衡）。开 DEBUG 看 `[GDR-POOL] fed` 是否只出现在非堵塞机 |
| 打掉理包机后包裹"消失" | 这不是 bug——共享池跟着存档走，包裹在池里，重放理包机即恢复（见 §3.9④）。只有打掉/扳手拆除 vault 才爆池；扩建 vault 会迁移 key（不爆） |
| 打掉/扩建 vault 后包裹没爆出来 | `ConnectivityHandlerMixin` 没注入成功（看启动日志有无 splitMulti 相关报错），或 `getControllerBE()` 返回 null（HEAD 时 controller 已被 null，见 §3.9③） |
| 游戏启动崩溃（打开仓库管理员时） | `@Inject` 参数类型和目标方法不匹配（见 §3.3） |
