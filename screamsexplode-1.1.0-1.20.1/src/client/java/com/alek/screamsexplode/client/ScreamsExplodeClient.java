package com.alek.screamsexplode.client;

import com.alek.screamsexplode.ScreamsExplodeMod;
import com.alek.screamsexplode.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import javax.sound.sampled.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ScreamsExplodeClient implements ClientModInitializer {

    private static void log(String msg) {
        try (PrintWriter out = new PrintWriter(new FileWriter("screamsexplode_debug.log", true))) {
            out.println(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + " " + msg);
        } catch (Exception e) {
        }
    }

    @Override
    public void onInitializeClient() {
        log("STARTED - ScreamsExplode Client initializing");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ScreamsExplodeMod.shouldExplode.getAndSet(false)) {
                log("Sending scream packet");
                ClientPlayNetworking.send(ScreamsExplodeMod.SCREAM_PACKET_ID, PacketByteBufs.create());
                ScreamsExplodeMod.packetsSent++;
            }
        });

        startMicCapture();
    }

    private void startMicCapture() {
        Thread t = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (!AudioSystem.isLineSupported(info)) {
                    log("48000 Hz not supported, trying 44100");
                    format = new AudioFormat(44100.0f, 16, 1, true, false);
                    info = new DataLine.Info(TargetDataLine.class, format);
                    if (!AudioSystem.isLineSupported(info)) {
                        log("NO MIC LINE SUPPORTED - mic won't work");
                        return;
                    }
                }

                TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(info);
                mic.open(format);
                mic.start();
                log("Mic opened: " + format.getSampleRate() + "Hz " + format.getSampleSizeInBits() + "bit " + (format.isBigEndian() ? "BE" : "LE"));

                int frameSize = 960;
                byte[] buffer = new byte[frameSize * 2];
                short[] pcm = new short[frameSize];

                while (!Thread.interrupted()) {
                    int bytesRead = mic.read(buffer, 0, buffer.length);
                    if (bytesRead < buffer.length) continue;

                    for (int i = 0; i < frameSize; i++) {
                        int low = buffer[i * 2] & 0xFF;
                        int high = buffer[i * 2 + 1] << 8;
                        pcm[i] = (short) (high | low);
                    }

                    ModConfig config = ModConfig.get();
                    if (!config.enabled) continue;

                    double threshold = config.threshold;
                    double sum = 0;
                    int peakCount = 0;

                    for (short sample : pcm) {
                        double normalized = Math.abs(sample) / 32767.0;
                        sum += normalized;
                        if (normalized > threshold) peakCount++;
                    }

                    double avg = sum / frameSize;
                    double peakRatio = peakCount / (double) frameSize;

                    ScreamsExplodeMod.lastAvgAmplitude = avg;
                    ScreamsExplodeMod.lastPeakRatio = peakRatio;

                    if (avg > threshold * 0.8 && peakRatio > 0.15) {
                        ScreamsExplodeMod.shouldExplode.set(true);
                        ScreamsExplodeMod.lastScreamDetectedAt = System.currentTimeMillis();
                        log("SCREAM DETECTED! avg=" + String.format("%.3f", avg) + " peak=" + String.format("%.3f", peakRatio));
                    }
                }

                mic.close();
            } catch (Exception e) {
                log("Mic capture error: " + e.getClass().getName() + ": " + e.getMessage());
                try (PrintWriter pw = new PrintWriter(new FileWriter("screamsexplode_debug.log", true))) { e.printStackTrace(pw); } catch (Exception ignored) {}
            }
        }, "ScreamDetect");
        t.setDaemon(true);
        t.start();
    }
}
