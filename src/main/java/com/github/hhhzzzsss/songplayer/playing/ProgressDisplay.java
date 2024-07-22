package com.github.hhhzzzsss.songplayer.playing;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Objects;

public class ProgressDisplay {
    private static ProgressDisplay instance = null;
    public static ProgressDisplay getInstance() {
        if (instance == null) {
            instance = new ProgressDisplay();
        }
        return instance;
    }
    private ProgressDisplay() {}

    public MutableText topText = Text.empty();
    public MutableText bottomText = Text.empty();
    public int fade = 0;

    public void setText(MutableText bottomText, MutableText topText) {
        this.bottomText = bottomText;
        this.topText = topText;
        fade = 100;
    }

    public void onRenderHUD(DrawContext context, int heldItemTooltipFade) {
        if (fade <= 0) {
            return;
        }
        int i = SongPlayer.MC.inGameHud.getTextRenderer().getWidth(topText);
        int j = (context.getScaledWindowWidth() - i) / 2;
        int k = context.getScaledWindowHeight() - (heldItemTooltipFade > 0 ? 72 : 59);

        int opacity = (int)((float)this.fade * 256.0F / 10.0F);
        if (opacity > 255) {
            opacity = 255;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Objects.requireNonNull(SongPlayer.MC.textRenderer);
        context.drawCenteredTextWithShadow(SongPlayer.MC.textRenderer, bottomText, j, k, 16777215 + (opacity << 24));
        context.drawCenteredTextWithShadow(SongPlayer.MC.textRenderer, bottomText, j, k, 16777215 + (opacity << 24));
        RenderSystem.disableBlend();
    }

    public void onTick() {
        if (fade > 0) {
            fade--;
        }
    }
}