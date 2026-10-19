package io.github.lollip0529.biscuits.mixin;

import io.github.lollip0529.biscuits.Biscuits;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Mixin(KeyBinding.class)
public class KeyBindingMixin {
    @Shadow
    @Final
    private static Map<String, KeyBinding> keysById;

    @Shadow
    @Final
    private static Map<InputUtil.Key, KeyBinding> keyToBindings;

    @Shadow
    @Final
    private static Map<String, Integer> categoryOrderMap;

    // controls screen sorts categories through this map and crashes on one it doesn't know
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void biscuits$category(CallbackInfo ci) {
        categoryOrderMap.put(Biscuits.CATEGORY, Collections.max(categoryOrderMap.values()) + 1);
    }

    // keep map only keys out of the lookup, otherwise binding one to A breaks strafing
    @Inject(method = "updateKeysByCode", at = @At("TAIL"))
    private static void biscuits$keepMapKeysOut(CallbackInfo ci) {
        // touch Biscuits before the loop - its keys register themselves on class load, can't happen mid iteration
        List<KeyBinding> mapOnly = Biscuits.MAP_ONLY;
        keyToBindings.clear();
        for (KeyBinding key : keysById.values()) {
            if (!mapOnly.contains(key)) {
                keyToBindings.put(InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()), key);
            }
        }
    }
}
