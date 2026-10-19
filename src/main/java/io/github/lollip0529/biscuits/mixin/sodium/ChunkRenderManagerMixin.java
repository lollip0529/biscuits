package io.github.lollip0529.biscuits.mixin.sodium;

import io.github.lollip0529.biscuits.Biscuits;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkRenderManager.class, remap = false)
public class ChunkRenderManagerMixin {
    // only runs when sodium decides the graph is dirty
    @Inject(method = "update", at = @At("HEAD"))
    private void biscuits$search(CallbackInfo ci) {
        Biscuits.searchRan();
    }
}
