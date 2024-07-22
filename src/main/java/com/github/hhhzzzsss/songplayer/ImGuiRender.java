package com.github.hhhzzzsss.songplayer;

import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.song.Song;
import dev.deftu.imgui.DearImGuiEntrypoint;
import imgui.ImGui;
import imgui.ImGuiTextFilter;
import imgui.type.ImBoolean;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Hand;

import java.io.File;
import java.util.Calendar;
import java.util.Objects;

public class ImGuiRender implements DearImGuiEntrypoint {

    public static boolean isShowing = false;
    private final ImGuiTextFilter textFilter = new ImGuiTextFilter();

//    @Override
//    public ImGuiRenderer createRenderer() {
//        return new ExampleImGuiRenderer();
//    }

    @Override
    public void render() {
        if (ImGui.isWindowFocused()) {

        }
        if (isShowing) {
            ImGui.begin("Song Player");

                if (Util.currentPlaylist != null) {
                    ImGui.text("Current Playlist: " + Util.currentPlaylist);
                } else {
                    ImGui.text("No Playlist Selected");
                }
                if (SongHandler.getInstance().currentSong != null) {
                    ImGui.text("Current Song: " + SongHandler.getInstance().currentSong.name);
                } else {
                    ImGui.text("No Song Playing");
                }
                if (ImGui.beginChild("Queue", ImGui.getContentRegionAvailX() - 4, 150, true)) {
                    if (!Objects.equals(Util.currentPlaylist, "")) {
                        for (String s : Util.playlistSongs) {
                            ImGui.text(s);
                        }
                    } else {
                        for (Song s : SongHandler.getInstance().songQueue) {
                            ImGui.text(s.name);
                        }
                    }
                    ImGui.endChild();
                }
                ImGui.textUnformatted("Songs");


                textFilter.draw("Filter");
                if (ImGui.beginChild("Filter", ImGui.getContentRegionAvailX() - 4, 150, true)) {
                    for (File songFile : SongPlayer.SONG_DIR.listFiles()) {
                        if (textFilter.passFilter(songFile.getName())) {
                            if (ImGui.button(songFile.getName())) {
                                //SongHandler.getInstance().playSong(new Song(songFile.getName()));
                                if (!Util.playlistSongs.isEmpty() || !Util.currentPlaylist.isEmpty()) {
                                    SongPlayer.addChatMessage("§cYou cannot use this command when a playlist is running! If you want to run an individual song, please type §4" + SongPlayer.prefix + "stop§c and try again.");
                                } else {
                                    SongHandler.getInstance().loadSong(songFile.getName(), SongPlayer.SONG_DIR);
                                }
                            }
                        }
                    }
                    ImGui.endChild();
                }

                if (ImGui.button("Play")) {
                    Util.resumeSongIfNeeded();
                }
                ImGui.sameLine();
                if (ImGui.button("Pause")) {
                    Util.pauseSongIfNeeded();
                }
                ImGui.sameLine();
                if (ImGui.button("Skip")) {
                    if (SongHandler.getInstance().currentSong == null) {
                        SongPlayer.addChatMessage("§6No song is currently playing");
                    } else {
                        Util.playcooldown = Calendar.getInstance().getTime().getTime() + 1500;
                        SongHandler.getInstance().currentSong = null;
                        Util.advancePlaylist();
                    }
                }
                ImGui.sameLine();
                if (ImGui.button("Stop")) {
                    if (SongHandler.getInstance().currentSong == null && SongHandler.getInstance().songQueue.isEmpty()) {
                        SongPlayer.addChatMessage("§6No song is currently playing");
                        Util.currentPlaylist = "";
                        Util.playlistSongs.clear();
                        return;
                    }
                    if (SongHandler.getInstance().stage != null && SongPlayer.useNoteblocksWhilePlaying && !SongHandler.getInstance().paused) {
                        SongHandler.getInstance().stage.movePlayerToStagePosition();
                    }
                    if (Util.currentPlaylist.isEmpty()) {
                        SongPlayer.addChatMessage("§6Stopped playing");
                    } else {
                        SongPlayer.addChatMessage("§6Stopped playlist §3" + Util.currentPlaylist);
                        Util.currentPlaylist = "";
                    }
                    SongHandler.getInstance().paused = false;
                    SongHandler.getInstance().cleanup(true);
                    Util.disableFlightIfNeeded();

                    if (SongHandler.oldItemHeld != null) {
                        PlayerInventory inventory = SongPlayer.MC.player.getInventory();
                        inventory.setStack(inventory.selectedSlot, SongHandler.oldItemHeld);
                        SongPlayer.MC.interactionManager.clickCreativeStack(SongPlayer.MC.player.getStackInHand(Hand.MAIN_HAND), 36 + inventory.selectedSlot);
                        SongHandler.oldItemHeld = null;
                    }
                }



            ImGui.end();
        }
    }

}
