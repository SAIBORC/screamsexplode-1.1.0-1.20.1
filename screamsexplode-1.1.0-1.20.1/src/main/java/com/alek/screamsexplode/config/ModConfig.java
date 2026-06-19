package com.alek.screamsexplode.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("screamsexplode.json");

    private static volatile ModConfig instance = new ModConfig();

    public volatile boolean enabled = true;
    public volatile double threshold = 0.7;
    public volatile int cooldownMs = 5000;
    public volatile float explosionPower = 3.0F;
    public volatile String selectedMicName = null;

    public static ModConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException e) {
                instance = new ModConfig();
            }
        }
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(instance));
        } catch (IOException e) {
        }
    }
}
