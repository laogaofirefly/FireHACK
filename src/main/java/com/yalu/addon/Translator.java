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
    private boolean classpathLoaded = false;

    public Translator() {
        loadClasspathDictionaries();
    }

    /** Returns the translated value, or null if neither Lotus nor JSON has it. */
    public String translate(String key, String fallback) {
        if (key == null || fallback == null) return fallback;
        if (lotusTranslations.isEmpty()) loadClasspathDictionaries();
        String value = lotusLookup(key);
        if (value == null) value = this.currentLangStrings.get(key);
        if (value != null) return value;
        // Log missing key (harmless)
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        langJson.addProperty(key, fallback);
        Path p = Paths.get("lang.json");
        try (BufferedWriter writer = Files.newBufferedWriter(p)) {
            gson.toJson(langJson, writer);
        } catch (IOException e) { /* ignore */ }
        return null;
    }

    /** Returns a translated value only when a real Chinese translation is present.
     *  English placeholder values (e.g. "scale" instead of "缩放") are ignored. */
    public String translateIfChinese(String key) {
        if (key == null) return null;
        if (lotusTranslations.isEmpty()) loadClasspathDictionaries();
        String value = lotusLookup(key);
        if (value == null) value = this.currentLangStrings.get(key);
        if (value != null && containsChinese(value)) return value;
        return null;
    }

    private static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isIdeographic(c) ||
                (c >= 0x3000 && c <= 0x303F) ||
                (c >= 0xFE30 && c <= 0xFE4F))
                return true;
        }
        return false;
    }

    private void loadClasspathDictionaries() {
        if (classpathLoaded) return;
        classpathLoaded = true;
        if (currentLangStrings.isEmpty()) {
            Map<String, String> strings = new HashMap<>();
            loadJsonResource("/assets/yalu/lang/zh_cn.json", strings);
            loadJsonResource("/assets/yalu/lang/en_us.json", strings);
            if (!strings.isEmpty()) {
                currentLangStrings = Collections.unmodifiableMap(strings);
            }
        }
        if (lotusTranslations.isEmpty()) {
            String[] files = {"meteor_cn.properties", "missing.properties", "missing_lotus.properties", "jefff_cn.properties"};
            for (String file : files) {
                try (InputStream stream = Translator.class.getResourceAsStream("/assets/yalu/lang/lotus/" + file);
                     InputStreamReader reader = stream != null
                         ? new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)
                         : null) {
                    if (reader == null) continue;
                    Properties props = new Properties();
                    props.load(reader);
                    for (String k : props.stringPropertyNames()) {
                        String v = props.getProperty(k);
                        if (v != null && !v.isBlank()) lotusTranslations.put(k, v);
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    private void loadJsonResource(String path, Map<String, String> target) {
        try (InputStream in = Translator.class.getResourceAsStream(path)) {
            if (in != null) Language.load(in, target::put);
        } catch (Exception ignored) { }
    }

    public synchronized void reload(ResourceManager manager) {
        if (manager == null) return;
        HashMap<String, String> strings = new HashMap<>();
        loadTranslations(manager, getCurrentLangCodes(), strings::put);
        this.currentLangStrings = Collections.unmodifiableMap(strings);
        loadLotusTranslations(manager);
        this.loadedManager = manager;
    }

    private Iterable<String> getCurrentLangCodes() {
        String mainLangCode = MinecraftClient.getInstance().getLanguageManager()
            .getLanguage().toLowerCase();
        ArrayList<String> langCodes = new ArrayList<>();
        langCodes.add("en_us.json");
        if (!"en_us.json".equals(mainLangCode))
            langCodes.add(mainLangCode);
        return langCodes;
    }

    private void loadTranslations(ResourceManager manager,
                                  Iterable<String> langCodes, BiConsumer<String, String> entryConsumer) {
        for (String langCode : langCodes) {
            String langFilePath = "lang/" + (langCode.endsWith(".json") ? langCode : langCode + ".json");
            Identifier langId = Identifier.of("yalu", langFilePath);
            for (Resource resource : manager.getAllResources(langId)) {
                try (InputStream stream = resource.getInputStream()) {
                    Language.load(stream, entryConsumer);
                } catch (IOException e) {
                    System.out.println("Failed to load translations for "
                        + langCode + " from pack " + resource.getPackId());
                    e.printStackTrace();
                }
            }
        }
    }

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
        String normalizedName = normalize(name);

        for (Map.Entry<String, String> entry : lotusTranslations.entrySet()) {
            String propertyKey = entry.getKey();
            boolean module = propertyKey.startsWith("meteor-miku_");
            boolean baritone = propertyKey.startsWith("baritone_");
            if (!module && !baritone) continue;
            boolean propertyDescription = propertyKey.endsWith("_d");
            if (propertyDescription != description) continue;
            if (key.startsWith("Setting.Meteor.") && !baritone && !module) continue;
            if (key.startsWith("Module.Meteor.") && !module) continue;
            String candidate = propertyKey.substring(propertyKey.indexOf('_') + 1);
            if (propertyDescription) candidate = candidate.substring(0, candidate.length() - 2);
            if (normalize(candidate).equals(normalizedName)
                || normalize(candidate).endsWith(normalizedName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fff]", "").toLowerCase(Locale.ROOT);
    }
}