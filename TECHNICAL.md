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

唯一的注入点在 `RepackagerBlockEntity.attemptToRepackage(IItemHandler)` 方法内，
目标行是：

```java
queuedExitingPackages.addAll(boxesToExport);   // 源码 L134
```

这是理包机把整理好的整批包裹塞进自己队列的那一瞬间。我们用 `@Redirect` 拦截这个
`addAll` 调用，把"全塞给自己"改成"按负载均衡分给所有兄弟理包机"。

### 2.2 核心算法：双层负载均衡（思路 A 快照分配 + 思路 B 动态再平衡）

模组用两层互补的机制实现并行：

- **第一层（思路 A，快照分配）**：`@Redirect` 拦截 `attemptToRepackage` 里的 `addAll`，在 winner
  repack 完成的那一刻，按各机当前队列深度把整批包切分到各私有队列。详见下方 ①②③。
- **第二层（思路 B，动态再平衡）**：`@Inject` 挂在 `attemptToSend` 头部，每次理包机被 lazyTick 唤醒
  （每 10 tick ≈ 0.5 秒）时，把"队列最深者"队尾的一小部分包偷给"队列最浅者"队尾。补上第一层
  "分配完就静态"的弱点：即使某台理包机下游堵塞导致积压，空闲兄弟也能动态分担。详见 ④。

第一层的 `@Redirect` 方法 `distributeAcrossRepackagers` 做三件事：

**① 找到所有兄弟理包机**（`findAvailableSiblingRepackagers`）

理包机贴在保险库（多方块结构）外围。要找到"同一保险库的所有理包机"，采用 flood-fill：
1. 从 winner 理包机的位置出发，找到它连接的保险库方块
2. 从该方块 flood-fill 整个保险库多方块结构（上限 200 块，防失控）
3. 对每个保险库方块扫描 6 邻居，找出其中的 `RepackagerBlockEntity`
4. 用 `InventoryIdentifier` 值相等（`equals`）确认兄弟理包机连的是同一个保险库。
   对 vault 该标识是 `Bounds(BoundingBox)`，只比几何坐标，不受能力实例代次影响（详见 §3.7）

> **为什么用 flood-fill 而不是固定半径扫描**：保险库可达 3×3×3 甚至更大，贴在对角的
> 两个理包机距离可能 >2。早期版本用半径 2 扫描，导致 9 理包机场景只找到 3~5 个。

**② 负载均衡切分**（贪心算法）

`boxesToExport` 是 `List<BigItemStack>`，但**真正要发的包裹数是每个 `BigItemStack.count`
的总和**（不是列表长度）。一个 60 合成的订单 = 1 个 `BigItemStack(count=60)`。

对每个 `BigItemStack`，把它 `count` 个单元**逐个**分配给当前队列最短的理包机：

```java
for (int u = 0; u < total; u++) {
    int best = 找当前 (currentLoad + share) 最小的 recipient;
    share[best]++;
}
```

这样队列更短的理包机（更闲）分到更多活，队列长的少分。`self`（winner）始终是候选之一，
保证无兄弟时行为退化为原版。

**③ 分发 + 客户端同步**

每个 recipient 拿到自己的 `share` 份额，构造新的 `BigItemStack(stack.copy(), share)` 加进
它的 `queuedExitingPackages`。对非 winner 的 recipient 调用 `notifyUpdate()` 确保客户端
播放发送动画。

**④ 动态再平衡（思路 B，第二层）**

第一层是"一次性快照"——分配完那一刻各机有多少活就定了。如果之后某台理包机下游堵塞（通向
动力合成器的路径被堵），它的包发不出去，队列越积越长，而其它机器却空着，快照分配无能为力。

第二层补这个缺口：`@Inject` 挂在 `attemptToSend` 头部，每次理包机被 lazyTick 唤醒（每 10 tick
≈ 0.5 秒）时先做一次再平衡。再平衡分**两种模式**，严格保护订单顺序（见下），不会把合成打乱：

- **堵塞恢复（优先）**：若检测到某台理包机"堵塞"——即 `heldBox` 非空但 `animationTicks == 0`（上个
  包的发送动画早结束了，但下游没把它抽走，说明下游断了；见 §3.8 的被动清空协议）——则把它的队列
  **彻底清空**（含队首）搬给没堵塞且负载最低的兄弟。这绕过了下面的保守阈值，因为堵塞机的负载**永远
  不会自己下降**。堵塞期间 tick 不碰队列（字节码已验证：取件分支被 `heldBox.isEmpty()` 守卫挡住），
  所以移除队首无并发竞争。**不碰堵塞机的 `heldBox`**：那个在手上等下游的包不在队列里，下游恢复后它
  会照常发出（所以堵塞机恢复后仍会输出 1 个 heldBox 里的包，这是物理下限）。
- **保守均衡（正常）**：无人堵塞时，只有 `maxLoad > 2`（donor 确实积压）且 `maxLoad - minLoad > 1`
  （严重失衡）才动手，每次只搬 `(max - min) / 2`（向下取整）——朝均衡方向收敛一步，不一次搬光，避免
  每 0.5 秒反复抖动。

两种模式共同遵守的订单顺序保护原则：

- **只从 donor 队尾取（保守模式）/ 可清空整个队列（堵塞模式）**：保守均衡模式绝不碰队首（tick 正在
  消费它），即便 donor 只剩一个元素也只切分不移除。堵塞恢复模式则可清空整个队列含队首——因为堵塞期
  tick 不碰队列，移除队首无竞争（见上）。两种模式下 receiver 都从队尾接收，FIFO 顺序保持。
- **加到 receiver 队尾**：receiver 先发完自己原有的队首，再发新追加的，FIFO 顺序保持。

> **"残留 3 个"问题（0.3.0 修复）**：早期版本只有保守均衡，被堵机的负载降到 2 时就被 `maxLoad <= 2`
> 阈值卡住不再搬运，加上队首保护那 1 个，导致被堵机恢复后**总是输出恰好 3 个**（2 个阈值残留 + 1 个
> 队首），与订单大小无关。引入堵塞检测后，被堵机队列被**彻底清空**（含队首——堵塞期间 tick 不碰队列，
> 字节码已验证 `heldBox.isEmpty()` 守卫挡住了取件分支，移除队首无竞争），残留降到 **1 个**（heldBox
> 那个"在手上等下游"的包，物理下限——要消掉它得动 heldBox 字段，会打断 tick 取件状态机，不做）。

### 2.3 防物品复制/丢失

- **不复制**：`count` 被切成互不相交的份额，每个包裹单元只进一个 recipient 的队列
- **不丢失**：每个 `BigItemStack` 处理后检查 `assigned == total`，不等就打 `SPLIT MISMATCH` 警告
- **再平衡守恒**：每次再平衡前后各算一次所有兄弟的 `pendingShipmentCount` 总和，必须相等（再平衡
  只搬运、不增减），不等打 `REBALANCE MISMATCH` 警告
- **不干扰原版碎片清理**：注入点在 L134（addAll）**之后于** L117-124（清空保险库里该 orderId
  的碎片）执行——其实 addAll 在清空之后，所以我们不动碎片逻辑，只动队列分配

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

> **诊断铁证**（仍适用于排查 sibling 漏找的其它成因，如 §3.6 的 flood-fill）：开启 `DEBUG_LOGGING`
> 后看 `[GDR-DIST] ... across N repackager(s)` 的 N。N 应等于实际理包机数；若仍偏小，
> 问题在 flood-fill 漏扫而非身份匹配。

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

- **路径 ①（已采用，0.3.0）**：完全不动 tick / heldBox / extractItem。再平衡只在各机私有
  `queuedExitingPackages` 之间搬队尾元素，发送状态机照原样跑。零死锁风险。
- **路径 ②（真·共享池，未采用）**：若要建全局池、让 tick 从池取件，就必须接管 heldBox 生命周期
  （下游 extractItem 协议被打断后需自己管 heldBox=EMPTY），死锁/物品复制风险高。这正是 §4.2 评估
  30-40% 成功率的根因。

---

## 4. 后续开发方向

### 4.1 动态负载均衡（思路 B）

**原思路 A 的局限**：分配是"一次性快照"——在 repack 完成那一刻决定谁分多少。
如果之后有理包机空闲下来，它不会去别的理包机队列里"抢"活。

思路 B 的目标是让空闲理包机能动态分担忙碌理包机的积压。设计阶段评估了两条路径：

#### 路径 ①：动态再平衡（已采用，0.3.0 实现）

**思路**：不动发送状态机，只在 `attemptToSend` 头部加一道再平衡——每次理包机被 lazyTick 唤醒时，
把"队列最深者"队尾的一小部分包偷给"队列最浅者"队尾。空闲理包机就能动态吃到原本堆在别机队列里的活。

**为什么选它**：完全不碰 `tick()` / `heldBox` / `extractItem`（见 §3.8 的被动清空协议），零死锁风险；
代码改动小、复用思路 A 的 sibling 发现与 count 切分；物品守卫容易做（再平衡前后总量守恒）。在"消除
快照静态性"这一核心目标上效果与真·共享池几乎相当（玩家体感无别），风险却低一个数量级。

**实现细节见 §2.2 ④**。订单顺序保护（队尾取、保守阈值、单轮收敛）见同节。

#### 路径 ②：真·共享池（未采用，未来可能方向）

**思路**：建一个 per-vault 全局 `SharedPackagePool`。winner repack 出来的整批**入共享池**（不入任何
私有队列）。各理包机 `tick()` 取件时**从共享池取**，而不是从自己的 `queuedExitingPackages` 取。

**实现要点**（若未来要做）：
- 新建 `SharedPackagePool` 类：per-vault 队列 + NBT 序列化（区块卸载时池里没发的包不能丢）。
- 改 `addAll` redirect：整批入共享池。
- 新增第二个 mixin 拦父类 `PackagerBlockEntity.tick()` 的 `queuedExitingPackages.get(0)`，handler 内
  `instanceof RepackagerBlockEntity` 早退（避免误伤普通打包机，因为 Repackager 不重写 tick）。
- **接管 heldBox 生命周期**（最大风险）：共享池打断了"包先进私有队列再被 tick 取出"的路径，须自己
  管理 `heldBox=EMPTY`，否则下游抽不动时 tick 取件死锁。

**为什么 0.3.0 没做**：字节码调研（§3.8）发现 vanilla 连续发包依赖 heldBox 的被动清空协议，拦 tick
取件会打断它，死锁/物品复制风险高——这正是原版 §4.1 估 30-40% 成功率的根因。路径 ① 已用低得多的
风险达成接近的动态均衡效果，所以路径 ② 暂不实现。

**如果未来要做，建议**：在新分支上开发，0.3.0 的 jar 作为保底；重点设计一个跨 tick 的"发送计数器"
对账机制（池入量 vs 各机发出量），确保即使共享池出错，也能从计数差发现物品增减；优先解决 heldBox
生命周期管理，这是成败关键。

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
   `goddamnrepackager-0.3.0-alpha.jar`（由 `gradle.properties` 里的 `mod_is_alpha=true` 控制，
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
- 每次订单快照分配打印 `[GDR-DIST]` 行：分配总量、distinct stack 数、参与理包机数、每个理包机
  分到的份额。
- 每次动态再平衡实际搬运时打印 `[GDR-REBAL]` 行：搬运的包裹单元数。
- `SPLIT MISMATCH`（快照分配）和 `REBALANCE MISMATCH`（再平衡）警告始终开启（那是真错误）。

### 常见问题排查

| 现象 | 可能原因 |
|---|---|
| Mixin 不触发，无报错 | MixinGradle 没配好，refmap 没生成（见 §3.1） |
| `mixin class is invalid` | refmap 缺失或 target 描述符错（见 §3.1） |
| 只有部分理包机工作 | ① 若用 0.2.0：老存档能力缓存代次不一致，**升级到 0.2.1+** 即解决（见 §3.7）；② sibling finder 漏找（保险库太大？检查 flood-fill）。用 `DEBUG_LOGGING` 的 `across N repackager(s)` 的 N 区分：0.2.1 下 N 仍偏小则是 ② |
| 产物数量不对 | 立即查 `SPLIT MISMATCH` / `REBALANCE MISMATCH` 警告；前者查快照 count 切分，后者查再平衡搬运（见 §2.3） |
| 下游堵塞时积压不消散 | 确认 0.3.0+（思路 B 再平衡 + 堵塞恢复已启用）；开 `DEBUG_LOGGING` 看 `[GDR-REBAL] STALLED-DONOR` 是否触发。堵塞机的队列应被彻底清空（恢复后只输出 heldBox 那 1 个，物理下限）。若堵塞机仍残留多个，检查 `isStalled` 判定（heldBox非空 && animationTicks==0） |
| 游戏启动崩溃（打开仓库管理员时） | `@Inject` 参数类型和目标方法不匹配（见 §3.3） |
