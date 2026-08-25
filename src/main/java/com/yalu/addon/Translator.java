package com.yalu.addon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;

public class Translator {
    private final JsonObject langJson = new JsonObject();
    private volatile Map<String, String> currentLangStrings = Collections.emptyMap();
    private volatile ResourceManager loadedManager;
    private final Map<String, String> lotusTranslations = new HashMap<>();

    public String translate(String key, String fallback) {
        if (key == null || fallback == null) return fallback;
        String value = this.currentLangStrings.get(key);
        if (value == null) value = lotusLookup(key);
        if(value != null){
            return value;
        } else {
            // Keep the original Meteor text when no translation is available.
            // The fallback is also recorded for debugging without affecting startup.
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            langJson.addProperty(key, fallback);
            Path path = Paths.get("lang.json");
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                gson.toJson(langJson, writer);
            } catch (IOException e) {
                // Translation logging must never break module or setting creation.
                e.printStackTrace();
            }
        }
        return fallback;
    }


    public synchronized void reload(ResourceManager manager)
    {
        if (manager == null || manager == loadedManager) return;
        HashMap<String, String> currentLangStrings = new HashMap<>();
        //从mixin获取管理器然后获取当前语言的语言代码，然后加载翻译文件
//		//这个方法会将语言文件内的键值对赋值给currentLangStrings（这是个HASHMAP（键值对））
        loadTranslations(manager, getCurrentLangCodes(),
            currentLangStrings::put);
        //设置不可变的map 也就是说现在这个currentLangStrings就是当前语言的键值对翻译了
        this.currentLangStrings = Collections.unmodifiableMap(currentLangStrings);
        loadLotusTranslations(manager);
        this.loadedManager = manager;
    }

    private Iterable<String> getCurrentLangCodes() {
        // Weird bug: Some users have their language set to "en_US" instead of
        // "en_us.json" for some reason. Last seen in 1.21.
        String mainLangCode = MinecraftClient.getInstance().getLanguageManager()
            .getLanguage().toLowerCase();

        ArrayList<String> langCodes = new ArrayList<>();
        langCodes.add("en_us.json");
        if(!"en_us.json".equals(mainLangCode))
            langCodes.add(mainLangCode);

        return langCodes;
    }

    private void loadTranslations(ResourceManager manager,
                                  Iterable<String> langCodes, BiConsumer<String, String> entryConsumer)
    {
        //遍历所有已经获取的语言代码
        for(String langCode : langCodes)
        {
            //设置路径
            String langFilePath = "lang/" + langCode + ".json";

            //注册语言ID
            Identifier langId = Identifier.of("yalu", langFilePath);

            for(Resource resource : manager.getAllResources(langId))
                try(InputStream stream = resource.getInputStream())
                {
                    Language.load(stream, entryConsumer);

                }catch(IOException e)
                {
                    System.out.println("Failed to load translations for "
                        + langCode + " from pack " + resource.getPackId());
                    e.printStackTrace();
                }
        }
    }
    /** Loads the supplied Lotus-i18n property dictionaries. */
    private void loadLotusTranslations(ResourceManager manager) {
        lotusTranslations.clear();
        String[] files = {"meteor_cn.properties", "missing.properties", "missing_lotus.properties", "jefff_cn.properties"};
        for (String file : files) {
            Identifier id = Identifier.of("yalu", "lang/lotus/" + file);
            for (Resource resource : manager.getAllResources(id)) {
                try (InputStream stream = resource.getInputStream();
                     InputStreamReader reader = new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                    Properties props = new Properties();
                    props.load(reader);
                    for (String k : props.stringPropertyNames()) {
                        String v = props.getProperty(k);
                        if (v != null && !v.isBlank()) lotusTranslations.put(k, v);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to load Lotus translations: " + file);
                }
            }
        }
    }

    private String lotusLookup(String key) {
        String base = key;
        boolean description = false;
        if (base.endsWith(".Description")) {
            description = true;
            base = base.substring(0, base.length() - ".Description".length());
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0) return null;
        String name = base.substring(dot + 1);
        String[] candidates;
        if (key.startsWith("Setting.Meteor.")) {
            candidates = description ? new String[]{"baritone_" + name + "_d", "meteor-miku_" + name + "_d"} : new String[]{"baritone_" + name, "meteor-miku_" + name};
        } else if (key.startsWith("Module.Meteor.")) {
            candidates = description ? new String[]{"meteor-miku_" + name + "_d"} : new String[]{"meteor-miku_" + name};
        } else return null;
        for (String candidate : candidates) {
            String result = lotusTranslations.get(candidate);
            if (result != null) return result;
        }
        return null;
    }

}
