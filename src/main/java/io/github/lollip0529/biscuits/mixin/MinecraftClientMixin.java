package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Biscuits;
import io.github.lollip0529.biscuits.Esp;
import io.github.lollip0529.biscuits.MapScreen;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "handleInputEvents", at = @At("TAIL"))
    private void biscuits$openMap(CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient) (Object) this;
        while (Biscuits.OPEN.wasPressed()) {
            if (mc.world != null && mc.currentScreen == null) {
                mc.openScreen(new MapScreen());
            }
        }
        while (Biscuits.ESP.wasPressed()) {
            if (mc.world != null) Esp.toggle(mc);
        }
    }
}
