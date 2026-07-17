package com.github.goddamnrepackager.mixin;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.repackager.PackageRepackageHelper;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Invoker bridge to PackageRepackageHelper's PROTECTED repackBasedOnRecipes.
 *
 * Partial repackaging calls this directly for non-final passes: it crafts
 * min(ordered, affordable) per recipe from a pooled InventorySummary (mutating
 * the pool by consuming ingredients) and returns one BigItemStack per recipe
 * whose count is how many crafts the current materials afforded. It does NOT
 * addAddress and does NOT export leftovers — exactly the behavior a partial
 * pass needs (vanilla's public repack() would additionally close out the order
 * by exporting all remaining items, which is only correct for the final pass).
 *
 * Interface mixin: @Invoker methods are turned into call bridges by the
 * transformer, not merged as new methods, so the §3.9.5 visibility rule
 * (no public/static plain methods in mixin classes) does not apply here.
 */
@Mixin(value = PackageRepackageHelper.class, remap = false)
public interface PackageRepackageHelperInvoker {

    @Invoker("repackBasedOnRecipes")
    List<BigItemStack> goddamnrepackager$repackBasedOnRecipes(
            InventorySummary availableItems,
            PackageOrderWithCrafts orderContext,
            String address,
            RandomSource random);
}
