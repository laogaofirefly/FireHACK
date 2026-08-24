package com.yalu.addon.mixin;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

import static com.yalu.addon.TranslateAddon.MC;
import static com.yalu.addon.TranslateAddon.TRANSLATOR;

@Mixin(value = Setting.class, remap = false, priority = 900)
public class SettingMixin {
    @Shadow @Final public String name;
    @Mutable
    @Final
    @Shadow
    public String title;
    @Mutable
    @Final
    @Shadow
    public String description;
    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void firehack$translate(CallbackInfo ci) {
        if (MC == null) return;
        try {
            TRANSLATOR.reload(MC.getResourceManager());
            this.title = TRANSLATOR.translate("Setting.Meteor." + this.name, this.name);
            this.description = TRANSLATOR.translate("Setting.Meteor." + this.name + ".Description", this.description);
        } catch (Throwable ignored) {
            // Preserve vanilla/Meteor values if a setting implementation changes.
        }
    }
}
