package com.yalu.addon;

import com.mojang.logging.LogUtils;
import com.yalu.addon.modules.AboutThisPlugin;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

public class TranslateAddon extends MeteorAddon {
    public static final String VERSION = "1.0.2";
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("I18n");

    /** Lazily resolve the client. DO NOT initialize statically: mixins (SettingMixin,
     *  ModuleMixin) reference this class during Fabric's mixin bootstrap, at which point
     *  MinecraftClient.getInstance() is still null and would freeze the whole translation
     *  system forever. */
    public static MinecraftClient MC() {
        return MinecraftClient.getInstance();
    }

    /** Lazily create the translator so classpath dictionaries are loaded when first needed,
     *  not during class-init (avoids touching the resource manager before it exists). */
    private static volatile Translator translator;
    public static Translator TRANSLATOR() {
        Translator t = translator;
        if (t == null) {
            synchronized (TranslateAddon.class) {
                t = translator;
                if (t == null) {
                    t = new Translator();
                    translator = t;
                }
            }
        }
        return t;
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new AboutThisPlugin());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.yalu.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("dingzhen-vape", "Meteor-I18n-Support-plugin");
    }
}