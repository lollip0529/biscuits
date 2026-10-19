package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Biscuits;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Mutable
    @Final
    @Shadow
    public KeyBinding[] keysAll;

    // before load() so the keys get read back from options.txt
    @Inject(method = "load", at = @At("HEAD"))
    private void biscuits$addKeys(CallbackInfo ci) {
        for (KeyBinding key : Biscuits.ALL) {
            if (!ArrayUtils.contains(this.keysAll, key)) {
                this.keysAll = ArrayUtils.add(this.keysAll, key);
            }
        }
    }
}
