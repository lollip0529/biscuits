package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Esp;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void biscuits$esp(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        Esp.hud(matrices);
    }
}
