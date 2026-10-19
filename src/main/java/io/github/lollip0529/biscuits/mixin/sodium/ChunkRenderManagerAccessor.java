package io.github.lollip0529.biscuits.mixin.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager;
import me.jellysquid.mods.sodium.client.render.chunk.cull.ChunkCuller;
import net.minecraft.client.util.math.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkRenderManager.class, remap = false)
public interface ChunkRenderManagerAccessor {
    @Accessor("culler")
    ChunkCuller biscuits$culler();

    @Accessor("useFogCulling")
    boolean biscuits$fogCulling();

    @Accessor("usePlanarFog")
    boolean biscuits$planarFog();

    @Accessor("fogRenderCutoff")
    double biscuits$fogCutoff();

    @Accessor("cameraX")
    float biscuits$cameraX();

    @Accessor("cameraY")
    float biscuits$cameraY();

    @Accessor("cameraZ")
    float biscuits$cameraZ();

    @Accessor("cameraVector")
    Vector3f biscuits$cameraVector();
}
