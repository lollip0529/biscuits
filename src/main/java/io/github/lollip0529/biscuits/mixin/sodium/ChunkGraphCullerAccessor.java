package io.github.lollip0529.biscuits.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphCuller;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphIterationQueue;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode;
import me.jellysquid.mods.sodium.client.util.math.FrustumExtended;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkGraphCuller.class, remap = false)
public interface ChunkGraphCullerAccessor {
    @Accessor("nodes")
    Long2ObjectMap<ChunkGraphNode> biscuits$nodes();

    @Accessor("visible")
    ChunkGraphIterationQueue biscuits$queue();

    @Accessor("frustum")
    FrustumExtended biscuits$frustum();

    @Accessor("useOcclusionCulling")
    boolean biscuits$occlusion();

    @Accessor("renderDistance")
    int biscuits$renderDistance();

    @Accessor("centerChunkX")
    int biscuits$centerX();

    @Accessor("centerChunkY")
    int biscuits$centerY();

    @Accessor("centerChunkZ")
    int biscuits$centerZ();
}
