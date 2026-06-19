package com.alek.screamsexplode.client.gui;

import com.alek.screamsexplode.config.ModConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ConfigScreenProvider {
    private static final Identifier FUNDDDR_TEXTURE = new Identifier("screamsexplode", "textures/gui/fundddr.png");

    public static Screen create(Screen parent) {
        return new Screen(Text.literal("Screams Explode Config")) {
            @Override
            protected void init() {
                super.init();
                ModConfig config = ModConfig.get();

                int centreX = width / 2;
                int y = height / 2 - 40;

                ButtonWidget toggleBtn = ButtonWidget.builder(
                        Text.literal(config.enabled ? "Enabled" : "Disabled"),
                        btn -> {
                            config.enabled = !config.enabled;
                            ModConfig.save();
                            btn.setMessage(Text.literal(config.enabled ? "Enabled" : "Disabled"));
                        }
                ).dimensions(centreX - 75, y, 150, 20).build();

                addDrawableChild(toggleBtn);

                SliderWidget thresholdSlider = new SliderWidget(
                    centreX - 75, y + 30, 150, 20,
                    Text.literal(String.format("Threshold: %.0f%%", config.threshold * 100)),
                    config.threshold
                ) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Text.literal(String.format("Threshold: %.0f%%", this.value * 100)));
                    }

                    @Override
                    protected void applyValue() {
                        config.threshold = this.value;
                        ModConfig.save();
                    }
                };

                addDrawableChild(thresholdSlider);
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, float delta) {
                renderBackground(context);

                int imgSize = 80;
                context.drawTexture(FUNDDDR_TEXTURE, width / 2 - imgSize / 2, 40, 0, 0, imgSize, imgSize, imgSize, imgSize);

                context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 130, 0xFFFFFF);
                context.drawCenteredTextWithShadow(textRenderer, Text.literal("Louder scream = lower threshold"), width / 2, height / 2 + 20, 0x808080);

                long time = System.currentTimeMillis();
                int rainbow = java.awt.Color.HSBtoRGB((time % 3000) / 3000f, 1f, 1f);
                String text = "SAIBORC :3";
                int textX = width - textRenderer.getWidth(text) - 10;
                context.drawTextWithShadow(textRenderer, Text.literal(text), textX, 10, rainbow);

                super.render(context, mouseX, mouseY, delta);
            }
        };
    }
}
