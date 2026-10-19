package io.github.lollip0529.biscuits.mixin.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkGraphNode.class, remap = false)
public interface ChunkGraphNodeAccessor {
    @Accessor("visibilityData")
    long biscuits$visibility();
}
