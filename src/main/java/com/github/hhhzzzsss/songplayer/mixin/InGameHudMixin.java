package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.playing.ProgressDisplay;
import com.mojang.blaze3d.systems.RenderSystem;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Shadow
    private int heldItemTooltipFade;

    @Inject(at = @At("TAIL"), method = "renderHeldItemTooltip(Lnet/minecraft/client/gui/DrawContext;)V")
    private void onRenderHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        ProgressDisplay.getInstance().onRenderHUD(context, heldItemTooltipFade);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        // imguiimpl create with window context
    }
}