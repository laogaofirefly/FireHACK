package com.yalu.addon.mixin;

import com.yalu.addon.TranslateAddon;
import meteordevelopment.meteorclient.settings.Setting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Setting.class, remap = false, priority = 900)
public class SettingMixin {
    @Shadow @Final public String name;
    @Mutable @Final @Shadow public String title;
    @Mutable @Final @Shadow public String description;

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void firehack$translate(CallbackInfo ci) {
        try {
            TranslateAddon.TRANSLATOR().reload(TranslateAddon.MC().getResourceManager());
            // Only apply real Chinese translations — English placeholder values
            // (e.g. "scale") must NOT destroy the auto-generated title (e.g. "Scale").
            String t = TranslateAddon.TRANSLATOR().translateIfChinese("Setting.Meteor." + this.name);
            if (t != null) this.title = t;
            String d = TranslateAddon.TRANSLATOR().translateIfChinese("Setting.Meteor." + this.name + ".Description");
            if (d != null) this.description = d;
        } catch (Throwable ignored) {
            // Preserve vanilla/Meteor values if a setting implementation changes.
        }
    }
}