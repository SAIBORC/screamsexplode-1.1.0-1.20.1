package com.alek.screamsexplode;

import com.alek.screamsexplode.config.ModConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreamsExplodeMod implements ModInitializer {
    public static final String MOD_ID = "screamsexplode";
    public static final Identifier SCREAM_PACKET_ID = new Identifier(MOD_ID, "scream");
    public static final AtomicBoolean shouldExplode = new AtomicBoolean(false);

    public static volatile double lastAvgAmplitude = 0;
    public static volatile double lastPeakRatio = 0;
    public static volatile long lastScreamDetectedAt = 0;
    public static volatile int packetsSent = 0;
    public static volatile int explosionsCreated = 0;

    private static long lastExplosionTime = 0;

    private static void log(String msg) {
        try (PrintWriter out = new PrintWriter(new FileWriter("screamsexplode_debug.log", true))) {
            out.println(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + " [Mod] " + msg);
        } catch (Exception e) {
        }
    }

    @Override
    public void onInitialize() {
        log("onInitialize called!");
        ModConfig.load();

        ServerPlayNetworking.registerGlobalReceiver(SCREAM_PACKET_ID, (server, player, handler, buf, sender) -> {
            log("Server received packet from " + player.getName().getString());
            ModConfig config = ModConfig.get();
            if (!config.enabled) {
                log("Mod disabled, skipping");
                return;
            }
            if (player.isRemoved()) return;

            long now = System.currentTimeMillis();
            if (now - lastExplosionTime < config.cooldownMs) {
                log("Cooldown active, skipping");
                return;
            }
            lastExplosionTime = now;

            log("Creating explosion at " + player.getPos());
            server.execute(() -> {
                var pos = player.getPos();
                player.getWorld().createExplosion(
                    null, pos.x, pos.y, pos.z,
                    config.explosionPower,
                    net.minecraft.world.World.ExplosionSourceType.MOB
                );
                explosionsCreated++;
                log("BOOM! #" + explosionsCreated);
            });
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("screamsexplode")
                .executes(context -> {
                    ModConfig config = ModConfig.get();
                    long now = System.currentTimeMillis();
                    long sinceLastDetect = lastScreamDetectedAt > 0 ? now - lastScreamDetectedAt : -1;

                    context.getSource().sendFeedback(() -> Text.literal("=== ScreamsExplode Debug ==="), false);
                    context.getSource().sendFeedback(() -> Text.literal("Enabled: " + config.enabled), false);
                    context.getSource().sendFeedback(() -> Text.literal("Threshold: " + config.threshold), false);
                    context.getSource().sendFeedback(() -> Text.literal("Explosion Power: " + config.explosionPower), false);
                    context.getSource().sendFeedback(() -> Text.literal("Cooldown: " + config.cooldownMs + "ms"), false);
                    context.getSource().sendFeedback(() -> Text.literal("Last avg amplitude: " + String.format("%.3f", lastAvgAmplitude) + " (need > " + String.format("%.3f", config.threshold * 0.8) + ")"), false);
                    context.getSource().sendFeedback(() -> Text.literal("Last peak ratio: " + String.format("%.3f", lastPeakRatio) + " (need > 0.15)"), false);
                    context.getSource().sendFeedback(() -> Text.literal("Ms since last scream detect: " + (sinceLastDetect >= 0 ? sinceLastDetect : "N/A")), false);
                    context.getSource().sendFeedback(() -> Text.literal("Packets sent to server: " + packetsSent), false);
                    context.getSource().sendFeedback(() -> Text.literal("Explosions created: " + explosionsCreated), false);
                    return 1;
                })
            );
        });
    }
}
