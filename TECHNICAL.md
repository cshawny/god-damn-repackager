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

### 2.2 核心算法：负载均衡分配（思路 A）

`@Redirect` 方法 `distributeAcrossRepackagers` 做三件事：

**① 找到所有兄弟理包机**（`findAvailableSiblingRepackagers`）

理包机贴在保险库（多方块结构）外围。要找到"同一保险库的所有理包机"，采用 flood-fill：
1. 从 winner 理包机的位置出发，找到它连接的保险库方块
2. 从该方块 flood-fill 整个保险库多方块结构（上限 200 块，防失控）
3. 对每个保险库方块扫描 6 邻居，找出其中的 `RepackagerBlockEntity`
4. 用 `IItemHandler` 身份匹配（`==`）确认兄弟理包机连的是同一个保险库

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

### 2.3 防物品复制/丢失

- **不复制**：`count` 被切成互不相交的份额，每个包裹单元只进一个 recipient 的队列
- **不丢失**：每个 `BigItemStack` 处理后检查 `assigned == total`，不等就打 `SPLIT MISMATCH` 警告
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

**解法**：flood-fill 整个保险库多方块结构（见 §2.2①）。`IItemHandler` 身份匹配作为
正确性保障——即使扫到别的理包机，连的不是同一个保险库也会被过滤。

---

## 4. 后续开发方向

### 4.1 思路 B：共享包裹池（动态负载均衡）

**当前思路 A 的局限**：分配是"一次性快照"——在 repack 完成那一刻决定谁分多少。
如果之后有理包机空闲下来，它不会去别的理包机队列里"抢"活。

**思路 B 的目标**：所有理包机共享一个"待发包裹池"，谁空了谁取下一个。真正的动态均衡。

**实现思路**：拦截 `PackagerBlockEntity.tick()` 里 L143-149 的取件逻辑
（`queuedExitingPackages.get(0)`），改成从一个共享池取。

**为什么没做（风险评估）**：
- `tick()` 是发送状态机的心脏，涉及 `heldBox` / `animationTicks` / 客户端动画同步
- 共享池的并发安全（虽服务端单线程，但 BlockEntity tick 顺序仍需小心）
- **物品复制/丢失风险极高**：发送逻辑被扰动后，最坏情况是"下单 60 收到 58"，排查极难
- 调试成本：作者无法自己跑游戏，全靠用户贴 log 迭代；深改动的调试周期不可控

**评估成功率：30~40%。** 不建议在思路 A 已稳定可用的情况下贸然做。

**如果要做，建议**：在新分支上开发，思路 A 的 jar 作为保底；重点设计一个"发送计数器"
机制，确保即使共享池出错，也能从计数差发现物品增减。

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
4. 发布 jar 在 `build/libs/goddamnrepackager-<version>.jar`

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

在 `GodDamnRepackager.java` 里把 `DEBUG_LOGGING = true` 重新编译。开启后每次订单分配
都会打印 `[GDR-DIST]` 行，显示：分配总量、distinct stack 数、参与理包机数、每个理包机
分到的份额。`SPLIT MISMATCH` 警告始终开启（那是真错误）。

### 常见问题排查

| 现象 | 可能原因 |
|---|---|
| Mixin 不触发，无报错 | MixinGradle 没配好，refmap 没生成（见 §3.1） |
| `mixin class is invalid` | refmap 缺失或 target 描述符错（见 §3.1） |
| 只有部分理包机工作 | sibling finder 漏找（保险库太大？检查 flood-fill） |
| 产物数量不对 | 立即查 `SPLIT MISMATCH` 警告；检查 count 切分逻辑 |
| 游戏启动崩溃（打开仓库管理员时） | `@Inject` 参数类型和目标方法不匹配（见 §3.3） |
