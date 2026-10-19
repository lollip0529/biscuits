package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Biscuits;
import io.github.lollip0529.biscuits.Esp;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void biscuits$begin(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera,
                                GameRenderer gameRenderer, LightmapTextureManager lightmap, Matrix4f projection, CallbackInfo ci) {
        // same matrices render() builds its Frustum from
        Biscuits.beginWorldFrame(matrices.peek().getModel(), projection, camera.getPos());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void biscuits$end(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera,
                              GameRenderer gameRenderer, LightmapTextureManager lightmap, Matrix4f projection, CallbackInfo ci) {
        Biscuits.endWorldFrame();
        Esp.render(matrices, camera);
    }

    @Inject(method = "drawBlockOutline", at = @At("HEAD"))
    private void biscuits$outline(MatrixStack matrices, VertexConsumer consumer, Entity entity, double cameraX, double cameraY, double cameraZ,
                                  BlockPos pos, BlockState state, CallbackInfo ci) {
        Biscuits.outlineDrawn(pos);
    }

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void biscuits$entity(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta,
                                 MatrixStack matrices, VertexConsumerProvider consumers, CallbackInfo ci) {
        Biscuits.entityDrawn(entity.getEntityId());
    }
}
