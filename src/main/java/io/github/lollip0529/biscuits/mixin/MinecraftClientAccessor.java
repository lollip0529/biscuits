package io.github.lollip0529.biscuits.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.profiler.ProfileResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    // what the f3 pie draws from, null while it's off
    @Accessor("tickProfilerResult")
    ProfileResult biscuits$pie();
}
