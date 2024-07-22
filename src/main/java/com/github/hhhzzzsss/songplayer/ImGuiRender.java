package com.github.hhhzzzsss.songplayer;

import dev.deftu.imgui.DearImGuiEntrypoint;
import dev.deftu.imgui.ImGuiRenderer;
import dev.deftu.imgui.*;
import dev.deftu.imgui.ImGuiLogic;
import imgui.*;
import 

public class ImGui implements DearImGuiEntrypoint {

//    @Override
//    public ImGuiRenderer createRenderer() {
//        return new ExampleImGuiRenderer();
//    }

    @Override
    public void render() {
        ImGui.showDemoWindow();
    }

}
