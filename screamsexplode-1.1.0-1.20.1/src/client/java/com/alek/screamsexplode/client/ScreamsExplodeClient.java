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
import java.util.ArrayList;
import java.util.List;

public class ScreamsExplodeClient implements ClientModInitializer {

    private static volatile boolean micRunning = false;
    private static volatile TargetDataLine micLine = null;
    private static Thread micThread = null;

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

        startCaptureThread();
    }

    public static void restartMicCapture() {
        log("Restarting mic capture...");
        micRunning = false;
        Thread oldThread = micThread;
        TargetDataLine oldLine = micLine;
        micThread = null;
        micLine = null;
        if (oldThread != null) {
            oldThread.interrupt();
            if (oldLine != null) {
                oldLine.close();
            }
            try { oldThread.join(1000); } catch (InterruptedException ignored) {}
        }
        startCaptureThread();
    }

    private static void startCaptureThread() {
        Thread t = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                TargetDataLine mic = tryOpenMic(info, format);
                if (mic == null) {
                    log("48000 Hz not supported, trying 44100");
                    format = new AudioFormat(44100.0f, 16, 1, true, false);
                    info = new DataLine.Info(TargetDataLine.class, format);
                    mic = tryOpenMic(info, format);
                    if (mic == null) {
                        log("NO MIC LINE SUPPORTED");
                        return;
                    }
                }

                micLine = mic;
                micRunning = true;
                log("Mic format: " + format.getSampleRate() + "Hz " + format.getSampleSizeInBits() + "bit " + (format.isBigEndian() ? "BE" : "LE"));

                int frameSize = 960;
                byte[] buffer = new byte[frameSize * 2];
                short[] pcm = new short[frameSize];

                while (!Thread.interrupted() && micRunning) {
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

                micRunning = false;
                if (micLine != null) {
                    micLine.close();
                    micLine = null;
                }
            } catch (Exception e) {
                micRunning = false;
                log("Mic capture error: " + e.getClass().getName() + ": " + e.getMessage());
                try (PrintWriter pw = new PrintWriter(new FileWriter("screamsexplode_debug.log", true))) { e.printStackTrace(pw); } catch (Exception ignored) {}
            }
        }, "ScreamDetect");
        micThread = t;
        t.setDaemon(true);
        t.start();
    }

    public static List<Mixer.Info> getAvailableMics() {
        List<Mixer.Info> result = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mi);
                Line.Info[] lines = mixer.getTargetLineInfo();
                for (Line.Info li : lines) {
                    if (li.getLineClass() == TargetDataLine.class) {
                        result.add(mi);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    public static String getCurrentMicName() {
        ModConfig config = ModConfig.get();
        if (config.selectedMicName != null && !config.selectedMicName.isEmpty()) {
            return config.selectedMicName;
        }
        return "Default";
    }

    private static TargetDataLine tryOpenMic(DataLine.Info info, AudioFormat format) {
        ModConfig config = ModConfig.get();
        String selectedName = config.selectedMicName;

        if (selectedName != null && !selectedName.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mi.getName().equals(selectedName) && mixer.isLineSupported(info)) {
                        TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                        line.open(format);
                        line.start();
                        log("Opened mic: " + mi.getName());
                        return line;
                    }
                } catch (Exception ignored) {}
            }
            log("Selected mic '" + selectedName + "' not found, using default");
            config.selectedMicName = null;
            ModConfig.save();
        }

        if (AudioSystem.isLineSupported(info)) {
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                log("Opened default mic");
                return line;
            } catch (Exception e) {
                log("Failed to open default mic: " + e.getMessage());
            }
        }
        return null;
    }
}
