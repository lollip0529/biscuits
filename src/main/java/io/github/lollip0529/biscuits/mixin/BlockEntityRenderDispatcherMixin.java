package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Biscuits;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {
    // the private overload, runs after the range check and only if there's a renderer
    @Inject(method = "render(Lnet/minecraft/client/render/block/entity/BlockEntityRenderer;Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"))
    private static void biscuits$drawn(BlockEntityRenderer<?> renderer, BlockEntity blockEntity, float tickDelta,
                                       MatrixStack matrices, VertexConsumerProvider consumers, CallbackInfo ci) {
        Biscuits.blockEntityDrawn(blockEntity.getPos().asLong());
    }
}
