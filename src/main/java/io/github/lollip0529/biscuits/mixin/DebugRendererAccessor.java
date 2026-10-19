package io.github.lollip0529.biscuits.mixin;

import net.minecraft.client.render.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DebugRenderer.class)
public interface DebugRendererAccessor {
    @Accessor("showChunkBorder")
    boolean biscuits$chunkBorders();
}
