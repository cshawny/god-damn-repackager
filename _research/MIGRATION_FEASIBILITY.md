# 1.20.1 Forge → 1.21.1 NeoForge 迁移可行性预研报告

> 分支:`neoforge-1.21.1`(基于 master / Forge 1.20.1 0.4.0)。
> 目标:在动手重构前,验证两个**高危未知数**,并核对所有 mixin 注入点是否仍在。
> 方法:直接对照 Create `mc1.21.1/dev` 分支源码(GitHub raw),配合 NeoForge 官方文档。
> 留档:`_research/create-1.21.1-src/` 存了本次读到的 9 个 Create 源文件;`create-1.21.1-build.gradle` / `create-1.21.1-gradle.properties` 存了 Create 自己的构建配置。

---

## 结论先行

**迁移技术可行,难度中等偏高,无阻塞性问题。** 之前评估的两个高危点全部降级:

| 高危点(预估) | 实测结论 | 难度 |
|---|---|---|
| `ConnectivityHandler` 变 interface + `splitMulti` 泛型化 → mixin 无法注入 | **风险解除**。泛型在字节码擦除后描述符仍是 `(Lnet/minecraft/world/level/block/entity/BlockEntity;)V`,handler `(BlockEntity, CallbackInfo)` 能正常匹配 | 无 |
| `BigItemStack.read/write` 被移除 → SharedPackagePool NBT 重写 | **风险确认但已有现成模板**。Create 自己的 `PackagerBlockEntity.write/read` 用 `CatnipCodecUtils.encode/decode(BigItemStack.CODEC, registries, ...)` 做完全相同的事,可直接照抄 | 中 |

**所有四个 mixin 注入目标在 1.21.1 都还在,核心字段全部 public 不变**,`heldBox` 被动清空协议(§3.8)和 0.4.0 七大陷阱(§3.9)的原理全部继续适用。

预估工作量:**2-4 天**(含用户实测)。

---

## 1. Create 1.21.1 的实际坐标(实测)

- **最新发布版**:Modrinth 上是 **6.0.10**(`create-1.21.1-6.0.10.jar`);dev 分支 `gradle.properties` 已是 **6.0.11**(未发布)。
- **Create 本体不上传 maven**——createmod maven 只发 Ponder/Flywheel/Vanillin。dev 时依赖 Create 要么走 Modrinth maven,要么直接下 jar。具体 dev 坐标待实际接 gradle 时确认(Modrinth maven 路径探测本次未命中,可能需要用 CurseForge maven 或本地 jar)。
- **Create 自己的依赖版本**(从其 `gradle.properties`):

| 依赖 | 1.20.1(我们) | 1.21.1(Create) |
|---|---|---|
| Minecraft | 1.20.1 | 1.21.1 |
| 加载器 | Forge 47.2.0 | NeoForge 21.1.219 |
| Create | 6.0.8-289 | 6.0.11(dev)/ 6.0.10(发布) |
| Ponder | 1.0.92 | 1.0.82(`net.createmod.ponder:ponder-neoforge`) |
| Flywheel | 1.0.5 | 1.0.6(独立 `flywheel-neoforge-api` + `flywheel-neoforge`) |
| Vanillin | — | **新增** `vanillin-neoforge-1.21.1:1.1.3-41`(Flywheel 的 UI 部分,拆出来了) |
| Registrate | MC1.20-1.3.3 | MC1.21-1.3.0+67 |
| JEI | 15.20.0.106 | 19.21.0.247 |

> **1.21.1 起 Flywheel 又拆出 Vanillin**——这是 1.20.1 没有的新依赖,gradle 里要加上,否则 Create runtime 起不来。

---

## 2. 注入点逐项核对(全绿)

### ✅ `Repackager.attemptToRepackage` 的 `addAll`(@Redirect)

源文件 `_research/create-1.21.1-src/content_logistics_packager_repackager_RepackagerBlockEntity.java` L140:

```java
queuedExitingPackages.addAll(boxesToExport);   // ← 还是这一行
```

`attemptToRepackage(IItemHandler)` 签名不变,`addAll` ordinal 0,**@Redirect 可直接移植,无需改动**。

### ✅ `PackagerBlockEntity.tick()V`(@Inject HEAD)

源文件 L159 `public void tick()`,L168-177 取件逻辑:

```java
if (!level.isClientSide() && !queuedExitingPackages.isEmpty() && heldBox.isEmpty()) {
    BigItemStack entry = queuedExitingPackages.get(0);
    heldBox = entry.stack.copy();
    ...
}
```

**和 TECHNICAL.md §3.8 描述一字不差**。`heldBox` 被动清空协议继续适用。

**关键 public 字段全部还在**:

| 字段 | 行 | 备注 |
|---|---|---|
| `public boolean redstonePowered` | L74 | `redstonePowered` 是 §3.9⑦ 红石门依赖的字段 |
| `public ItemStack heldBox` | L79 | |
| `public List<BigItemStack> queuedExitingPackages` | L82 | |
| `public int animationTicks` | L87 | |
| `public InvManipulationBehaviour targetInventory` | (父类,未在本次源文件中复查) | 1.20.1 是 public,1.21.1 保留(待重构时复查) |

**`@Mixin(PackagerBlockEntity.class, remap=false) @Inject(method="tick()V", at=@At("HEAD"))` + `instanceof RepackagerBlockEntity` 守卫 + `if (!self.redstonePowered) return`** —— 这套策略 A 模式**原样可移植**。

### ✅ `ConnectivityHandler.splitMulti`(@Inject HEAD)

**最大未知数,实测解除**。源文件 L280:

```java
public static <T extends BlockEntity & IMultiBlockEntityContainer> void splitMulti(T be) {
    splitMultiAndInvalidate(be, null, false);
}
```

泛型 + 交叉类型边界,但**JVM 擦除规则**:`<T extends A & B>` 擦除到第一个 bound `A = BlockEntity`,所以字节码里的方法描述符是:

```
splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V
```

和 1.20.1 完全一致。mixin handler `(BlockEntity, CallbackInfo)` 能匹配。**`@Inject(method="splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", at=@At("HEAD"))` 原样可移植。**

> `ConnectivityHandler` 仍然是**普通 class**(不是 interface——之前 agent 报告说是 interface 是误判,实测源码 L31 `public class ConnectivityHandler`)。所以 `@Mixin(ConnectivityHandler.class)` 不变。

附带好消息:`splitMultiAndInvalidate` 里现在明确有 `if (be instanceof IMultiBlockEntityContainer.Inventory inv && inv.hasInventory()) be.getLevel().invalidateCapabilities(...)`(L362-363)——capability 失效逻辑在销毁路径里被强化了,符合我们的预期。

### ✅ `ItemVaultBlockEntity.notifyMultiUpdated()V`(@Inject HEAD)

源文件 L390 `public void notifyMultiUpdated()`,无参。**@Inject HEAD 原样可移植。**

附带验证:
- `initCapability()` 的 BoundingBox 公式(L375-378)和我们的 `VaultBoxTracker.boxOf()` **完全一致**:
  ```java
  BlockPos farCorner = alongZ ? worldPosition.offset(radius, radius, length)
                              : worldPosition.offset(length, radius, radius);
  ```
- `getWidth()`→`radius`、`getHeight()`→`length`、`getMainConnectionAxis()`、`getControllerBE()` 都在(public)。

---

## 3. 工具类逐项核对

### ✅ `VaultIdentity`——无需改动

`InventoryIdentifier.Bounds` 仍是 record(L78 `_research/create-1.21.1-src/api_packager_InventoryIdentifier.java`),`.bounds()` 访问器在。`IdentifiedInventory` 变成 record(L12),但 `.identifier()` 访问器同名,**我们只调这一个方法,不受影响**。

### ✅ `VaultBoxTracker`——无需改动

依赖的 `ItemVaultBlockEntity.getBlockPos()/getWidth()/getHeight()/getMainConnectionAxis()` 都在,公式一致。

### ⚠️ `SharedPackagePool`——需要重写 NBT 部分(中等工作量)

这是本次预研最需要小心的一块。

**1.21.1 的三个 API 变化同时砸过来:**

1. **`SavedData` API 重构**:
   - 老:`computeIfAbsent(loadFn, supplier, id)`(3 参数)
   - 新:`computeIfAbsent(SavedData.Factory<T>, id)`(2 参数),Factory 把 new/load 绑一起
   - `save` 签名变:`save(CompoundTag, HolderLookup.Provider)`(多了 `registries`)
   - load 也变成 `BiFunction<CompoundTag, HolderLookup.Provider, T>`

2. **`BigItemStack.read(CompoundTag)` / `write()` 没了**,改成 `Codec<BigItemStack>`。

3. **1.20.5+ 的 `ItemStack` 是 data-component 化的**,NBT codec 必须配 `HolderLookup.Provider` 才能解码 components。

**但——Create 已经在 `PackagerBlockEntity.write/read`(L589/L609)给了我们现成模板**:

```java
// 读
queuedExitingPackages = NBTHelper.readCompoundList(
    compound.getList("QueuedExitingPackages", Tag.TAG_COMPOUND),
    c -> CatnipCodecUtils.decode(BigItemStack.CODEC, registries, c).orElseThrow());

// 写
compound.put("QueuedExitingPackages", NBTHelper.writeCompoundList(queuedExitingPackages, bis -> {
    if (CatnipCodecUtils.encode(BigItemStack.CODEC, registries, bis)
        .orElse(new CompoundTag()) instanceof CompoundTag ct)
        return ct;
    return new CompoundTag();
}));
```

`CatnipCodecUtils` 来自 `net.createmod.catnip.nbt.CatnipCodec`(Create 的 catnip 依赖,我们通过 Create 传递依赖能拿到)。`SharedPackagePool.load/save` 照搬这个模式即可。

**重写后的 `SharedPackagePool` 骨架**(预估,真正写的时候要实测):

```java
public static SharedPackagePool get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(
        new SavedData.Factory<>(
            SharedPackagePool::new,
            (tag, registries) -> load(tag, registries),
            null /* DataFixType */),
        DATA_ID);
}

public static SharedPackagePool load(CompoundTag root, HolderLookup.Provider registries) {
    // ... 用 CatnipCodecUtils.decode(BigItemStack.CODEC, registries, ...) 替换 BigItemStack.read(...)
}

@Override
public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
    // ... 用 CatnipCodecUtils.encode(BigItemStack.CODEC, registries, ...) 替换 BigItemStack.write()
}
```

**额外约束**:`poll/deposit/migrateKey/drainAndDrop` 这些非 NBT 操作**不变**(它们只操作 `BigItemStack.count` 和 `stack.copy()`,不碰序列化)。`stack.copy()` 在 1.21.1 语义不变。

---

## 4. 入口点 + 构建系统(机械工作)

### `GodDamnRepackager.java`

| 1.20.1 | 1.21.1 |
|---|---|
| `import net.minecraftforge.fml.common.Mod;` | `import net.neoforged.fml.common.Mod;` |
| `import net.minecraftforge.eventbus.api.IEventBus;` | `import net.neoforged.bus.api.IEventBus;` |
| `import net.minecraftforge.common.MinecraftForge;` | `import net.neoforged.neoforge.common.NeoForge;` |
| `FMLJavaModLoadingContext.get().getModEventBus()` | 构造器注入:`public GodDamnRepackager(IEventBus modBus, ModContainer container)` |
| `MinecraftForge.EVENT_BUS.register(this)` | `NeoForge.EVENT_BUS.register(this)` |

### `build.gradle`——大改(完全替换)

- 插件:`net.minecraftforge.gradle` + `org.spongepowered.mixin` → `net.neoforged.moddev`
- 删 `mixin { add ... config ... }` 块、删所有 refmap 相关配置(MixinGradle 那套全废)
- 删 `minecraft { ... }` 块,改用 `neoForge { ... }` DSL
- 删 jar manifest 里的 `MixinConfigs` 属性(NeoForge 不再读它)
- 依赖坐标全换(见 §1 表)
- `java.toolchain.languageVersion = 21`(不是 17)

### `mods.toml` → `neoforge.mods.toml`

- 文件名改、放到 `src/main/templates/META-INF/`(ModDevGradle 约定)
- 加 `[[mixins]] config = "goddamnrepackager.mixins.json"` 表项(NeoForge 靠这个发现 mixin 配置,不靠 manifest)

### `goddamnrepackager.mixins.json`

- `compatibilityLevel` 从 `JAVA_17` 改 `JAVA_21`
- 其余不变

### `g.sh`

- `JAVA_HOME` 改 JDK 21(系统上 `/c/Program Files/Java/jdk-22` 可作 JDK 21+ 的替代——但要确认 JDK 21 才是 1.21.1 的目标;JDK 22 也能编译但官方建议 21)

---

## 5. 1.21.1 特有的新 footgun(写进 TECHNICAL.md §3.x)

### ⚠️ `isController()` 在 1.21.1 更危险了(§3.9③ 升级版)

1.20.1 的 `isController()` 是 `return controller == null;`(teardown 后每个 part 谎报 true)。

1.21.1 改成(L203):
```java
public boolean isController() {
    return controller == null || worldPosition.getX() == controller.getX()
        && worldPosition.getY() == controller.getY()
        && worldPosition.getZ() == controller.getZ();
}
```

**注意运算符优先级**:`||` 优先级低于 `&&`,等价于 `controller == null || (X相等 && Y相等 && Z相等)`。

含义:
- **正常 controller**:`controller == null` → true ✓
- **正常 part**:`controller != null`,坐标不等 → false ✓
- **teardown 后的 part**:`removeController()` 把 `controller = null`(L228),于是 `controller == null` → **true**!(footgun 仍在)
- **新 footgun**:如果某 part 的 `worldPosition` 恰好等于 controller 字段记录的坐标(罕见),也会判 true。

**结论**:`getControllerBE()` 仍是唯一可靠判定,§3.9③ 警告继续适用,且 `isController()` 在 1.21.1 不可靠性来源略变。

### ⚠️ Capability 系统大改(LazyOptional → Capabilities 静态查找)

Forge 的 `getCapability(...)` + `LazyOptional<IItemHandler>` 在 NeoForge 被 `net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK` + `RegisterCapabilitiesEvent` 取代(见 `RepackagerBlockEntity.registerCapabilities` L144-158、`ItemVaultBlockEntity.registerCapabilities` L69-80)。

**对我们的影响**:我们**不直接**调 `getCapability`——走的是 Create 自己的 `r.targetInventory`(它内部已经处理了),所以可能不受影响。但 `targetInventory.getInventory()` 的返回类型在 1.21.1 是否还稳定,重构时需要复查(本次预研没读 `InvManipulationBehaviour.getInventory()`,只读了 `getIdentifiedInventory()`)。

---

## 6. 工作量分解(下一步重构的 task 清单)

| # | 任务 | 风险 | 预估 |
|---|---|---|---|
| 1 | 改 `g.sh` 用 JDK 21 | 低 | 5 分钟 |
| 2 | 重写 `build.gradle`(ModDevGradle + NeoForge DSL + 新依赖坐标) | **中**——Modrinth maven 坐标没验证,可能要 CurseForge maven 或本地 jar | 1-2 小时 |
| 3 | `mods.toml` → `neoforge.mods.toml`,加 `[[mixins]]` | 低 | 15 分钟 |
| 4 | `mixins.json` 改 `JAVA_21` | 低 | 1 分钟 |
| 5 | `GodDamnRepackager.java` 包名 + 构造器注入 | 低 | 10 分钟 |
| 6 | `SharedPackagePool` 重写 NBT(Factory + CatnipCodecUtils) | **中**——照抄 Create 模板,但要实测 `CatnipCodecUtils` 是否传递依赖可见 | 1 小时 |
| 7 | 四个 mixin 文件:复查 + 修 import(Forge → NeoForge 包名,mixin 目标本身不变) | 低 | 30 分钟 |
| 8 | `./g.sh clean build` 通过 | **中**——依赖坐标 + ModDevGradle 第一次配可能有坑 | 1-3 小时 |
| 9 | `./g.sh runClient` 启动不崩 + mixin 触发(看 `[GDR-POOL]` 日志) | **必须用户实测** | 用户时间 |
| 10 | 功能实测:下单、多机理包、vault 销毁/reshape | **必须用户实测** | 用户时间 |
| 11 | 更新 `AGENTS.md` / `TECHNICAL.md`(§3.1 MixinGradle 作废、§5 构建、§3.9③ footgun 升级) | 低 | 1 小时 |

**第 2 步(依赖坐标)是下一个真正的不确定点**——Modrinth maven 路径本次没命中,真要接 gradle 时可能需要换 CurseForge maven 或下 jar 塞进 `libs/`。这个等开始重构时再处理。

---

## 7. 决策建议

1. **B 阶段预研完成,无阻塞。可以进入 A 阶段(实际重构)。**
2. 重构顺序按 §6 的 task 清单走,**先做 1-7(机械改动),再做 8(build 通过),最后交用户做 9-10(实测)**。
3. 文档更新(11)放最后,避免重构中改动反复返工。
4. **master 分支保持不动**,所有改动留在 `neoforge-1.21.1` 分支。
