package com.yalu.addon.mixin;

import com.yalu.addon.TranslateAddon;
import com.yalu.addon.Translator;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static com.yalu.addon.TranslateAddon.MC;
import static com.yalu.addon.TranslateAddon.TRANSLATOR;

/** Optional compatibility mixin: translation must never prevent a module from loading. */
@Mixin(value = Module.class, remap = false, priority = 900)
public abstract class ModuleMixin {
    @Mutable @Shadow @Final public String title;
    @Mutable @Shadow @Final public String description;
    @Shadow @Final public String name;

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void firehack$translate(CallbackInfo ci) {
        if (MC == null || name == null) return;
        try {
            TRANSLATOR.reload(MC.getResourceManager());
            // Only apply real Chinese translations.
            String t = TRANSLATOR.translateIfChinese("Module.Meteor." + name);
            if (t != null) title = t;
            String d = TRANSLATOR.translateIfChinese("Module.Meteor." + name + ".Description");
            if (d != null) description = d;
        } catch (Throwable ignored) {
            // Keep the original Meteor text if a future Meteor build changes its internals.
        }
    }
}
