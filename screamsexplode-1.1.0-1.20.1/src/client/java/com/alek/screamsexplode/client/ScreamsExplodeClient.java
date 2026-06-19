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
    private static volatile float currentSampleRate = 48000;
    private static volatile int currentBits = 16;

    private static final float[] RATES = {192000, 176400, 96000, 88200, 48000, 44100, 22050, 11025};

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
                TargetDataLine mic = null;
                AudioFormat chosenFormat = null;

                for (float rate : RATES) {
                    AudioFormat fmt16 = new AudioFormat(rate, 16, 1, true, false);
                    DataLine.Info info16 = new DataLine.Info(TargetDataLine.class, fmt16);
                    mic = tryOpenMic(info16, fmt16);
                    if (mic != null) { chosenFormat = fmt16; break; }

                    AudioFormat fmt16be = new AudioFormat(rate, 16, 1, true, true);
                    DataLine.Info info16be = new DataLine.Info(TargetDataLine.class, fmt16be);
                    mic = tryOpenMic(info16be, fmt16be);
                    if (mic != null) { chosenFormat = fmt16be; break; }

                    AudioFormat fmt8 = new AudioFormat(rate, 8, 1, false, false);
                    DataLine.Info info8 = new DataLine.Info(TargetDataLine.class, fmt8);
                    mic = tryOpenMic(info8, fmt8);
                    if (mic != null) { chosenFormat = fmt8; break; }
                }

                if (mic == null) {
                    log("NO MIC LINE SUPPORTED");
                    return;
                }

                currentSampleRate = chosenFormat.getSampleRate();
                currentBits = chosenFormat.getSampleSizeInBits();

                micLine = mic;
                micRunning = true;
                log("Mic format: " + currentSampleRate + "Hz " + currentBits + "bit");

                int frameSize = 960;
                int bytesPerSample = currentBits / 8;
                byte[] buffer = new byte[frameSize * bytesPerSample];
                short[] pcm = new short[frameSize];

                while (!Thread.interrupted() && micRunning) {
                    int bytesRead = mic.read(buffer, 0, buffer.length);
                    if (bytesRead < buffer.length) continue;

                    if (currentBits == 16) {
                        for (int i = 0; i < frameSize; i++) {
                            int low = buffer[i * 2] & 0xFF;
                            int high = buffer[i * 2 + 1] << 8;
                            pcm[i] = (short) (high | low);
                        }
                    } else {
                        for (int i = 0; i < frameSize; i++) {
                            pcm[i] = (short) ((buffer[i] & 0xFF) - 128);
                        }
                    }

                    ModConfig config = ModConfig.get();
                    if (!config.enabled) continue;

                    double threshold = config.threshold;
                    double sum = 0;
                    int peakCount = 0;
                    double norm = (currentBits == 16) ? 32767.0 : 127.0;

                    for (short sample : pcm) {
                        double normalized = Math.abs(sample) / norm;
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
