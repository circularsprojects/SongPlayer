package com.github.hhhzzzsss.songplayer.playing;

import com.github.hhhzzzsss.songplayer.FakePlayerEntity;
import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.song.*;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.NoteBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.*;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SongHandler {
    public static ItemStack oldItemHeld = null;
    private static SongHandler instance = null;
    public static SongHandler getInstance() {
        if (instance == null) {
            instance = new SongHandler();
        }
        return instance;
    }
    private SongHandler() {}

    public static SongLoaderThread loaderThread = null;
    public LinkedList<Song> songQueue = new LinkedList<>();
    public Song currentSong = null;
    public Stage stage = null;
    public boolean building = false;
    public boolean paused = false;
    int bandaidpatch;

    public void onUpdate(Boolean tick) {
        if (currentSong == null && songQueue.size() > 0) {
            setSong(songQueue.poll());
        }
        if (loaderThread != null && !loaderThread.isAlive()) {
            if (loaderThread.exception != null) {
                SongPlayer.addChatMessage("§cFailed to load song: §4" + loaderThread.exception.getMessage());
                Util.advancePlaylist();
            } else {
                if (currentSong == null) {
                    setSong(loaderThread.song);
                } else {
                    queueSong(loaderThread.song);
                }
            }
            loaderThread = null;
        }
        if (SongPlayer.recording) {
            setRecordingDisplay();
        }
        if (currentSong == null) {
            if (songQueue.isEmpty() && Util.playlistSongs.isEmpty()) {
                if (stage != null || SongPlayer.fakePlayer != null) {
                    if (stage != null) {
                        stage.movePlayerToStagePosition(false, false, false);
                        Util.disableFlightIfNeeded();
                    }
                    cleanup(false);
                }
            }
            return;
        }

        if (stage == null) {
            stage = new Stage();
            if (songQueue.isEmpty() && Util.playlistSongs.isEmpty()) {
                stage.movePlayerToStagePosition(false, false, false);
            }
        }
        if (SongPlayer.showFakePlayer && SongPlayer.fakePlayer == null && SongPlayer.useNoteblocksWhilePlaying && !paused) {
            SongPlayer.fakePlayer = new FakePlayerEntity();
            SongPlayer.fakePlayer.copyStagePosAndPlayerLook();
        }
        if (!SongPlayer.showFakePlayer && SongPlayer.fakePlayer != null) {
            SongPlayer.removeFakePlayer();
        }
        checkCommandCache();
        if (SongPlayer.useNoteblocksWhilePlaying && !paused) {
            SongPlayer.MC.player.getAbilities().allowFlying = true;
        }
        if (building && SongPlayer.useNoteblocksWhilePlaying) {
            handleBuilding(tick);
        } else {
            handlePlaying(tick);
        }
    }

    public void loadSong(String location, File dir) {
        if (loaderThread != null) {
            SongPlayer.addChatMessage("§cAlready loading a song, cannot load another");
            return;
        }
        if (SongPlayer.recording) {
            SongPlayer.addChatMessage("§cCannot load song while recording noteblocks!");
            return;
        }
        try {
            loaderThread = new SongLoaderThread(location, dir);
            if (Util.currentPlaylist.isEmpty()) {
                SongPlayer.addChatMessage("§6Loading §3" + location + "");
            }
            loaderThread.start();
        } catch (IOException e) {
            SongPlayer.addChatMessage("§cFailed to load song: §4" + e.getMessage());
        }
    }

    public void setSong(Song song) {
        currentSong = song;
        if (!SongPlayer.useNoteblocksWhilePlaying) {
            Util.assignCommandBlocks(false);
            building = false;
            return;
        }
        if (stage == null) {
            stage = new Stage();
            stage.movePlayerToStagePosition(true, true, false);
        } else {
            stage.movePlayerToStagePosition(true, false, true);
        }
        pitchesLeft = 0;
        modifyingblock = false;
        stage.checkBuildStatus(currentSong);
        if (!Util.hasEnoughNoteblocks()) {
            return;
        }
        if (stage.nothingToBuild()) { //nothing else needs to be built. Go in survival if needed and play the song
            setSurvivalIfNeeded();
            return;
        }
        if (SongPlayer.switchGamemode) {
            SongPlayer.addChatMessage("§6Building noteblocks");
        } else {
            SongPlayer.addChatMessage("§6Tuning noteblocks");
        }
        building = true;
        setCreativeIfNeeded();
    }

    private void queueSong(Song song) {
        if (!Util.playlistSongs.isEmpty() || !Util.currentPlaylist.isEmpty()) {
            SongPlayer.addChatMessage("§cUnable to add song to queue. Playlist is in progress.");
            return;
        }
        songQueue.add(song);
        if (Util.currentPlaylist.isEmpty()) {
            SongPlayer.addChatMessage("§6Added song to queue: §3" + song.name);
        }
    }

    // Runs every tick

    private int buildCooldown = 0;
    private int updatePlayerPosCooldown = 0;
    private int pitchesLeft = 0;
    private int outoftuneindex = 0;
    private long buildEndDelay = Calendar.getInstance().getTime().getTime();
    private boolean modifyingblock = false;


    private HashMap<BlockPos, Integer> outOfTune = new HashMap<>();
    private void handleBuilding(boolean tick) {
        if (paused) {
            setPlayProgressDisplay();
            return;
        }
        if (SongPlayer.MC.player.isDead()) {
            stage.outOfTuneBlocks.clear();
            Util.pauseSongIfNeeded();
            SongPlayer.addChatMessage("§6Your song has been paused because you died.\n §6Go back to your stage or find a new location and type §3" + SongPlayer.prefix + "resume§6 to resume playing.");
            return;
        }
        if (!SongPlayer.useNoteblocksWhilePlaying) {
            return;
        }

        //handle cooldown
        setBuildProgressDisplay();

        if (buildCooldown > 0) {
            if (tick || SongPlayer.useFramesInsteadOfTicks) {
                buildCooldown--;
            }
            return;
        }

        if (!SongPlayer.useNoteblocksWhilePlaying) {
            if (updatePlayerPosCooldown > 0 && (tick || SongPlayer.useFramesInsteadOfTicks)) {
                updatePlayerPosCooldown--;
            }
        }

        ClientWorld world = SongPlayer.MC.world;
        PlayerInventory inventory = SongPlayer.MC.player.getInventory();

        if (SongPlayer.switchGamemode) {
            outoftuneindex = 0;

            if (stage.nothingToBuild()) {
                if (buildEndDelay > Calendar.getInstance().getTime().getTime()) {
                    stage.checkBuildStatus(currentSong);
                    return;
                }
                if (oldItemHeld != null) {
                    inventory.main.set(inventory.selectedSlot, oldItemHeld);
                    SongPlayer.MC.interactionManager.clickCreativeStack(SongPlayer.MC.player.getStackInHand(Hand.MAIN_HAND), 36 + inventory.selectedSlot);
                    oldItemHeld = null;
                }
                setSurvivalIfNeeded();
                if (SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL) {
                    return;
                }
                building = false;
                stage.outOfTuneBlocks.clear();
                modifyingblock = false;
                pitchesLeft = 0;
                return;
            }

            if (SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.CREATIVE) {
                return;
            }

            //break blocks that need to be broken if any exist
            if (!stage.requiredBreaks.isEmpty()) {
                BlockPos bp = stage.requiredBreaks.poll();
                attackBlock(bp);
                buildCooldown = SongPlayer.buildDelay;
                buildEndDelay = Calendar.getInstance().getTime().getTime() + 500;
                return;
            }

            //check if there needs to be any building
            if (!stage.missingNotes.isEmpty()) {
                int desiredNoteId = stage.missingNotes.pollFirstEntry().getKey();
                BlockPos bp = stage.noteblockPositions.get(desiredNoteId);

                int blockId = Block.getRawIdFromState(world.getBlockState(bp));
                if (blockId % 2 == 0) { //fixes issue with powered=true blockstate
                    blockId += 1;
                }
                int currentNoteId = (blockId - SongPlayer.NOTEBLOCK_BASE_ID) / 2;
                if (currentNoteId != desiredNoteId) {
                    holdNoteblock(desiredNoteId);
                    if (blockId != 0) {
                        attackBlock(bp);
                    }
                    placeBlock(bp);
                }
                buildCooldown = SongPlayer.buildDelay;
                buildEndDelay = Calendar.getInstance().getTime().getTime() + 500;
                return;
            }
        } else { //survival only mode
            if (outoftuneindex >= stage.outOfTuneBlocks.size() && stage.outOfTuneBlocks.size() > 0) {
                if (buildEndDelay > Calendar.getInstance().getTime().getTime()) {
                    return;
                }
                outoftuneindex = 0;
                outOfTune.clear();
                int notright = 0;
                for (BlockPos test : new ArrayList<>(stage.outOfTuneBlocks.keySet())) {
                    int blockId = Block.getRawIdFromState(world.getBlockState(test));
                    if (blockId % 2 == 0) { //fixes issue with powered=true blockstate
                        blockId += 1;
                    }
                    if (blockId < SongPlayer.NOTEBLOCK_BASE_ID || blockId > SongPlayer.NOTEBLOCK_BASE_ID + 800) {
                        continue;
                    }

                    int currentBlockId = (blockId - SongPlayer.NOTEBLOCK_BASE_ID) / 2;
                    int currentPitchId = currentBlockId % 25;
                    int wantedNoteId = stage.outOfTuneBlocks.get(test);
                    int wantedPitchId = wantedNoteId % 25;

                    if (currentPitchId != wantedPitchId) {
                        notright++;
                        outOfTune.put(test, wantedNoteId);
                    }
                }
                stage.outOfTuneBlocks.clear();
                if (notright == 0) {
                    stage.missingNotes.clear();
                    outOfTune.clear();
                    buildEndDelay = Calendar.getInstance().getTime().getTime() + 1000;
                    stage.checkBuildStatus(currentSong);
                    return;
                } else {
                    stage.outOfTuneBlocks.putAll(outOfTune);
                    buildEndDelay = Calendar.getInstance().getTime().getTime() + 1000;
                }
            }

            //check if anything needs to be done
            if (stage.hasPitchModification() == 0) {
                outoftuneindex = 0;
                if (buildEndDelay > Calendar.getInstance().getTime().getTime()) {
                    return;
                }
                stage.checkBuildStatus(currentSong);
                if (stage.outOfTuneBlocks.size() > 0) { //aaagh someone be messing with ur noteblocks, or your ping is over a full second behind.
                    return;
                }
                building = false;
                setSurvivalIfNeeded();
                stage.outOfTuneBlocks.clear();
                modifyingblock = false;
                pitchesLeft = 0;
                return;
            }

            if (stage.outOfTuneBlocks.isEmpty()) {
                buildCooldown = SongPlayer.buildDelay;
                stage.checkBuildStatus(currentSong);
                pitchesLeft = 0;
                outoftuneindex = 0;
                modifyingblock = false;
                return;
            }

            buildEndDelay = Calendar.getInstance().getTime().getTime() + 1000;
            //rebuilding
            Set<Map.Entry<BlockPos, Integer>> entrySet = stage.outOfTuneBlocks.entrySet();
            List<Map.Entry<BlockPos, Integer>> entryList = new ArrayList<>(entrySet);
            int desiredNoteId = entryList.get(outoftuneindex).getValue();
            BlockPos bp = entryList.get(outoftuneindex).getKey();
            if (bp == null) {
                pitchesLeft = 0;
                outoftuneindex++;
                modifyingblock = false;
                return;
            }

            int blockId = Block.getRawIdFromState(world.getBlockState(bp));
            if (blockId % 2 == 0) { //fixes issue with powered=true blockstate
                blockId += 1;
            }

            if (blockId < SongPlayer.NOTEBLOCK_BASE_ID || blockId > SongPlayer.NOTEBLOCK_BASE_ID + 800) { //target block is not a noteblock, skip it
                pitchesLeft = 0;
                outoftuneindex++;
                modifyingblock = false;
                return;
            }
            if (pitchesLeft > 0 && modifyingblock) {
                placeBlock(bp);
                pitchesLeft--;
            } else {
                if (modifyingblock) {
                    pitchesLeft = 0;
                    outoftuneindex++;
                    modifyingblock = false;
                    return;
                }

                int currentNoteId = ((blockId - SongPlayer.NOTEBLOCK_BASE_ID) / 2) % 25;
                int currentPitch = currentNoteId % 25;
                int neededPitch = desiredNoteId % 25;
                int amountOfPitches = neededPitch - currentPitch;
                if (currentPitch == neededPitch) {
                    modifyingblock = false;
                    outoftuneindex++;
                    return;
                }
                if (amountOfPitches < 0) {
                    amountOfPitches += 25;
                }
                pitchesLeft = amountOfPitches;
                modifyingblock = true;
            }
        }
        buildEndDelay = Calendar.getInstance().getTime().getTime() + 500;
        buildCooldown = SongPlayer.buildDelay;
    }
    private void setBuildProgressDisplay() {
        MutableText text = Text.empty();
        MutableText empty = Text.empty(); //why bother re-writing code when you can do a rubberband fix?
        if (SongPlayer.switchGamemode) {
            if (stage.totalMissingNotes == 0) {
                return;
            }
            text.append(Text.literal("Building noteblocks | ").formatted(Formatting.GOLD));
            text.append(Text.literal((stage.totalMissingNotes - stage.missingNotes.size() + "/" + stage.totalMissingNotes)).formatted(Formatting.DARK_AQUA));
        } else {
            int progress = stage.totalOutOfTuneNotes - stage.hasPitchModification(); // (stage.totalOutOfTuneNotes - (stage.outOfTuneBlocks.size() + stage.hasPitchModification()));
            if (stage.totalOutOfTuneNotes == 0) {
                return;
            }
            text.append(Text.literal("Tuning noteblocks | ").formatted(Formatting.GOLD));
            text.append(Text.literal(progress + "/" + stage.totalOutOfTuneNotes).formatted(Formatting.DARK_AQUA));
        }
        ProgressDisplay.getInstance().setText(text, empty);
    }

    private void setRecordingDisplay() {
        MutableText text = Text.empty();
        MutableText subtext = Text.empty();

        if (SongPlayer.recordingPaused) {
            text.append("§6Recording | Paused");
        } else if (!SongPlayer.recordingActive) {
            text.append("§6Recording | waiting for noteblock to be played");
        } else {
            text.append("§6Recording");
        }
        subtext.append("§6notes: §30§6 | time: §3" + Util.formatTime(SongPlayer.recordingtick * 50) + "§6 | ");
        ProgressDisplay.getInstance().setText(text, subtext);
    }

    // Runs every frame
    private void handlePlaying(boolean tick) {
        setPlayProgressDisplay();
        if (paused) {
            currentSong.pause();
            return;
        }
        if (SongPlayer.useNoteblocksWhilePlaying) {
            if (SongPlayer.MC.player.isDead()) {
                Util.pauseSongIfNeeded();
                SongPlayer.addChatMessage("§6Your song has been paused because you died.\n §6Go back to your stage or find a new location and type §3" + SongPlayer.prefix + "resume§6 to resume playing.");
                return;
            }
            if (SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL || SongPlayer.MC.player.isSleeping()) {
                currentSong.pause();
                return;
            }
            if (tick) {
                if (SongPlayer.switchGamemode || SongPlayer.requireExactInstruments) {
                    stage.checkBuildStatus(currentSong);
                } else {
                    if (stage.hasBreakingModification()) { //only refresh the stage if at least one of the noteblocks are not the correct pitch so your game doesn't turn into a slideshow when playing
                        stage.checkBuildStatus(currentSong);
                    }
                }
            }
            if (!Util.hasEnoughNoteblocks()) {
                return;
            }
            if (stage.hasPitchModification() > 0) {
                building = true;
                currentSong.pause();
                SongPlayer.addChatMessage("§6Stage was altered. Retuning!");
                return;
            }
            if (!stage.nothingToBuild()) {
                if (!SongPlayer.switchGamemode) {
                    if (!tick) {
                        return;
                    }
                    paused = true;
                    SongPlayer.addChatMessage("§6Stage was altered and too many noteblocks were removed or can't be played. Please repair the stage and then type §3" + SongPlayer.prefix + "resume");
                    return;
                }
                building = true;
                setCreativeIfNeeded();
                Util.enableFlightIfNeeded();
                currentSong.pause();
                SongPlayer.addChatMessage("§6Stage was altered. Rebuilding!");
                return;
            }
        }

        if (Calendar.getInstance().getTime().getTime() < Util.playcooldown) {
            return;
        }

        //cooldown is over!

        if (!tick) {
            return;
        }
        currentSong.play();
        boolean somethingPlayed = false;
        currentSong.advanceTime();
        ClientPlayerEntity player = SongPlayer.MC.player;
        if (player.isCreative() || player.isSpectator()) {
            setSurvivalIfNeeded();
        }
        if (updatePlayerPosCooldown < 1) {
            updatePlayerPosCooldown = 10;
            Util.playerPosX = player.getX();
            Util.playerPosZ = player.getZ();
        }
        SoundEvent[] soundlist = {SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), SoundEvents.BLOCK_NOTE_BLOCK_SNARE.value(), SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundEvents.BLOCK_NOTE_BLOCK_GUITAR.value(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value(), SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE.value(), SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL.value(), SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value(), SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundEvents.BLOCK_NOTE_BLOCK_BANJO.value(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()};
        World world = SongPlayer.MC.player.getWorld();
        ArrayList<Integer> playedNotes = new ArrayList<>();
        while (currentSong.reachedNextNote()) {
            Note note = currentSong.getNextNote();
            Byte voln = note.volume;
            float volfloat = (float) (voln / 127.0);
            String volume = String.valueOf(volfloat);
            if (SongPlayer.parseVolume) {
                if (SongPlayer.ignoreNoteThreshold > voln) { //skip note - too quiet
                    continue;
                }
                if (volume.length() > 4) {
                    volume = volume.substring(0, 6);
                    if (volume.endsWith(".")) {
                        volume = volume + "0";
                    } else if (!volume.contains(".")) {
                        volume = volume + ".0";
                    }
                }
            }
            if (playedNotes.contains(note.noteId)) {
                continue;
            }
            playedNotes.add(note.noteId);
            if (SongPlayer.useNoteblocksWhilePlaying) {
                BlockPos bp = stage.noteblockPositions.get(note.noteId);
                if (bp != null) {
                    attackBlock(bp);
                    somethingPlayed = true;
                }
            } else if (SongPlayer.useCommandsForPlaying) {
                if (SongPlayer.disablecommandplaynote) {
                    break;
                }
                String[] instrumentNames = {"harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};
                int instrument = note.noteId / 25;
                int pitchID = (note.noteId % 25);
                double pitch = Math.pow(2, (pitchID + note.pitchCorrection/100.0 - 12) / 12);
                if (pitch > 2.0) pitch = 2.0;
                if (pitch < 0.5) pitch = 0.5;
                String command;
                if (!SongPlayer.parseVolume) {
                    volume = "1.0";
                }
                command = SongPlayer.playSoundCommand.replace("{type}", instrumentNames[instrument]).replace("{volume}", String.valueOf(volume)).replace("{pitch}", Double.toString(pitch)).replace("{panning}", String.valueOf((note.panning-100)/100.0*2));
                if (SongPlayer.includeCommandBlocks) {
                    Util.sendCommandWithCommandblocks(command);
                } else {
                    SongPlayer.MC.getNetworkHandler().sendCommand(command);
                }
            } else { //play client-side
                if (SongPlayer.parseVolume) {
                    player.playSound(soundlist[note.noteId / 25], volfloat, (float) Math.pow(2, (note.noteId % 25 + note.pitchCorrection/100.0 - 12) / 12));
                } else {
                    world.playSound(Util.playerPosX, player.getY() + 3000000, Util.playerPosZ, soundlist[note.noteId / 25], SoundCategory.RECORDS, 30000000, (float) Math.pow(2, (note.noteId % 25 + note.pitchCorrection/100.0 - 12) / 12), false);
                }
            }
        }

        if (somethingPlayed) {
            stopAttack();
        }
        if (currentSong.finished()) {
            Util.playcooldown = Calendar.getInstance().getTime().getTime() + 1500;
            if (Util.currentPlaylist.isEmpty()) {
                SongPlayer.addChatMessage("§6Done playing §3" + currentSong.name);
            }
            currentSong = null;
            Util.advancePlaylist();
        }
    }

    public void setPlayProgressDisplay() {
        long currentTime = Math.min(currentSong.time, currentSong.length);
        long totalTime = currentSong.length;

        MutableText text = Text.empty();
        MutableText empty = Text.empty(); //I should use this for something... Thanks hhhzzzsss!
        if (paused) {
            text.append(Text.literal("Paused ").formatted(Formatting.GOLD));
        } else {
            text.append(Text.literal("Now playing ").formatted(Formatting.GOLD));
        }
        text.append(Text.literal(currentSong.name).formatted(Formatting.BLUE))
        .append(Text.literal(" | ").formatted(Formatting.GOLD))
        .append(Text.literal(String.format("%s/%s", Util.formatTime(currentTime), Util.formatTime(totalTime))).formatted(Formatting.DARK_AQUA));
        if (currentSong.looping) {
            if (currentSong.loopCount > 0) {
                text.append(Text.literal(String.format(" | Looping (%d/%d)", currentSong.currentLoop, currentSong.loopCount)).formatted(Formatting.GOLD));
            } else {
                text.append(Text.literal(" | Looping enabled").formatted(Formatting.GOLD));
            }
        }
        ProgressDisplay.getInstance().setText(text, empty);
        if (SongPlayer.useCommandsForPlaying && !Util.lastTimeExecuted.equalsIgnoreCase(Util.formatTime(currentTime))) {
            if (SongPlayer.disablecommanddisplayprogress) {
                return;
            }
            Util.lastTimeExecuted = Util.formatTime(currentTime);
            String midiname = currentSong.name;
            String rawcommand = SongPlayer.showProgressCommand;
            String command = rawcommand.replace("{MIDI}", midiname).replace("{CurrentTime}", Util.formatTime(currentTime)).replace("{SongTime}", Util.formatTime(totalTime));
            int cmdlength = command.length();
            if (cmdlength > 254) {
                while (cmdlength > 250) {
                    midiname = midiname.substring(0, midiname.length() - 1);
                    cmdlength -= 1;
                }
                midiname = midiname + "...";
                command = rawcommand.replace("{MIDI}", midiname).replace("{CurrentTime}", Util.formatTime(currentTime)).replace("{SongTime}", Util.formatTime(totalTime));
            }
            if (SongPlayer.useCommandsForPlaying) {
                if (SongPlayer.includeCommandBlocks) {
                    Util.sendCommandWithCommandblocks(command);
                } else {
                    SongPlayer.MC.getNetworkHandler().sendCommand(command);
                }
            }
        }
    }

    public void cleanup(boolean includePlaylist) {
        if (!includePlaylist && Util.playlistSongs.size() > 0) {
            return;
        }
        currentSong = null;
        songQueue.clear();
        stage = null;
        paused = false;
        Util.availableCommandBlocks.clear();
        Util.playlistSongs.clear();
        Util.currentPlaylist = "";
        Util.playlistIndex = 0;
        Util.loopPlaylist = false;
        SongPlayer.removeFakePlayer();
        ProgressDisplay.getInstance().setText(Text.literal(""), Text.literal(""));
    }

    public void onNotIngame() {
        currentSong = null;
        songQueue.clear();
        Util.playlistSongs.clear();
        Util.currentPlaylist = "";
        Util.playlistIndex = 0;
    }

    private long lastCommandTime = System.currentTimeMillis();
    private String cachedCommand = null;
    private void sendGamemodeCommand(String command) {
        cachedCommand = command;
    }
    private void checkCommandCache() {
        //does not handle useCommandsForPlaying mode
        if (cachedCommand == null) return;
        if (System.currentTimeMillis() >= lastCommandTime + 1500 || (SongPlayer.MC.player.hasPermissionLevel(2) && System.currentTimeMillis() >= lastCommandTime + 250)) {
            SongPlayer.MC.getNetworkHandler().sendCommand(cachedCommand);
            cachedCommand = null;
            lastCommandTime = System.currentTimeMillis();
        }
    }
    private void setCreativeIfNeeded() {
        cachedCommand = null;
        if (!SongPlayer.switchGamemode) {
            return;
        }
        if (SongPlayer.disablecommandcreative) {
            return;
        }
        if (SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.CREATIVE) {
            sendGamemodeCommand(SongPlayer.creativeCommand);
        }
    }
    private void setSurvivalIfNeeded() {
        cachedCommand = null;
        if (!SongPlayer.switchGamemode) {
            return;
        }
        if (SongPlayer.disablecommandsurvival) {
            return;
        }
        if (oldItemHeld != null) {
            CreativeInventoryActionC2SPacket packet = new CreativeInventoryActionC2SPacket(SongPlayer.MC.player.getInventory().selectedSlot + 36, oldItemHeld);
            SongPlayer.MC.player.networkHandler.sendPacket(packet);
            PlayerInventory inventory = SongPlayer.MC.player.getInventory();
            SongPlayer.MC.player.getInventory().main.set(inventory.selectedSlot, oldItemHeld.copy());
            SongPlayer.MC.interactionManager.clickCreativeStack(SongPlayer.MC.player.getStackInHand(Hand.MAIN_HAND), 36 + inventory.selectedSlot);
            oldItemHeld = null;
        }
        if (SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL) {
            sendGamemodeCommand(SongPlayer.survivalCommand);
        }
    }

    //private final Instrument[] instruments = {Instrument.HARP, Instrument.BASEDRUM, Instrument.SNARE, Instrument.HAT, Instrument.BASS, Instrument.FLUTE, Instrument.BELL, Instrument.GUITAR, Instrument.CHIME, Instrument.XYLOPHONE, Instrument.IRON_XYLOPHONE, Instrument.COW_BELL, Instrument.DIDGERIDOO, Instrument.BIT, Instrument.BANJO, Instrument.PLING};
    private final NoteBlockInstrument[] instruments = {NoteBlockInstrument.HARP, NoteBlockInstrument.BASEDRUM, NoteBlockInstrument.SNARE, NoteBlockInstrument.HAT, NoteBlockInstrument.BASS, NoteBlockInstrument.FLUTE, NoteBlockInstrument.BELL, NoteBlockInstrument.GUITAR, NoteBlockInstrument.CHIME, NoteBlockInstrument.XYLOPHONE, NoteBlockInstrument.IRON_XYLOPHONE, NoteBlockInstrument.COW_BELL, NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.BIT, NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING};
    private final String[] instrumentNames = {"harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};
    private void holdNoteblock(int id) {
        PlayerInventory inventory = SongPlayer.MC.player.getInventory();
        if (oldItemHeld == null) {
            oldItemHeld = inventory.getMainHandStack();
        }
        bandaidpatch = id;
        int instrument = id/25;
        int note = id%25;

        ItemStack noteblock = Items.NOTE_BLOCK.getDefaultStack();
        BlockStateComponent bsc = new BlockStateComponent(Map.of(
                "instrument", instrumentNames[instrument],
                "note", Integer.toString(note)
        ));
        noteblock.set(DataComponentTypes.BLOCK_STATE, bsc);

        inventory.main.set(inventory.selectedSlot, noteblock);
        SongPlayer.MC.interactionManager.clickCreativeStack(SongPlayer.MC.player.getStackInHand(Hand.MAIN_HAND), 36 + inventory.selectedSlot);
    }

    private void placeBlock(BlockPos bp) {
        double fx = Math.max(0.0, Math.min(1.0, (stage.position.getX() - bp.getX())));
        double fy = Math.max(0.0, Math.min(1.0, (stage.position.getY() + 0.0 - bp.getY())));
        double fz = Math.max(0.0, Math.min(1.0, (stage.position.getZ() - bp.getZ())));
        fx += bp.getX();
        fy += bp.getY();
        fz += bp.getZ();

        if (SongPlayer.rotate) {
            float[] pitchandyaw = Util.getAngleAtBlock(bp);
            PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.LookAndOnGround(pitchandyaw[1], pitchandyaw[0], true);
            SongPlayer.MC.player.networkHandler.sendPacket(packet);
        }
        if (SongPlayer.usePacketsOnlyWhilePlaying) {
            if (SongPlayer.switchGamemode) {
                SongPlayer.MC.player.getWorld().playSound(bp.getX(), bp.getY(), bp.getZ(), Blocks.NOTE_BLOCK.getDefaultState().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 0.8f, true);
                //SongPlayer.MC.player.getWorld().setBlockState(bp, Blocks.NOTE_BLOCK.getDefaultState().with(Properties.INSTRUMENT, instruments[bandaidpatch/25]).with(Properties.NOTE, bandaidpatch%25));

                //new BlockState(Map.of(
                //                        "instrument", instrumentNames[bandaidpatch/25],
                //                        "note", Integer.toString(bandaidpatch%25)
                //                ))
//                SongPlayer.MC.player.getWorld().setBlockState(bp, Blocks.NOTE_BLOCK.getDefaultState().with(Properties.BLOCK_STATE, new BlockStateComponent(Map.of(
//                        "instrument", instrumentNames[bandaidpatch/25],
//                        "note", Integer.toString(bandaidpatch%25)
//                ))));

                // use something other than properties.block_state. it doesn't work
                SongPlayer.MC.player.getWorld().setBlockState(bp, Blocks.NOTE_BLOCK.getDefaultState().with(Properties.INSTRUMENT, instruments[bandaidpatch/25]).with(Properties.NOTE, bandaidpatch%25));
            }
            PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(SongPlayer.MC.player.getActiveHand(), new BlockHitResult(new Vec3d(fx, fy, fz), Direction.DOWN, bp, false), 0);
            SongPlayer.MC.getNetworkHandler().sendPacket(packet);
        } else {
            SongPlayer.MC.interactionManager.interactBlock(SongPlayer.MC.player, Hand.MAIN_HAND, new BlockHitResult(new Vec3d(fx, fy, fz), Direction.DOWN, bp, false));
        }
        if (SongPlayer.swing) {
            Util.swingHand();
        }
    }
    private void attackBlock(BlockPos bp) {
        ClientPlayerEntity player = SongPlayer.MC.player;
        if (SongPlayer.rotate) {
            float[] pitchandyaw = Util.getAngleAtBlock(bp);
            PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.LookAndOnGround(pitchandyaw[1], pitchandyaw[0], true);
            player.networkHandler.sendPacket(packet);
        }
        if (SongPlayer.usePacketsOnlyWhilePlaying) {
            if (SongHandler.getInstance().building && SongPlayer.switchGamemode) {
                SongPlayer.MC.world.playSound(bp.getX(), bp.getY(), bp.getZ(), SongPlayer.MC.world.getBlockState(bp).getBlock().getDefaultState().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 0.8f, true);
                SongPlayer.MC.world.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
            PlayerActionC2SPacket attack = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, bp, Direction.DOWN);
            PlayerActionC2SPacket stopattack = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, bp, Direction.DOWN);
            SongPlayer.MC.getNetworkHandler().sendPacket(attack);
            SongPlayer.MC.getNetworkHandler().sendPacket(stopattack);
        } else {
            SongPlayer.MC.interactionManager.attackBlock(bp, Direction.DOWN);
        }

        if (SongPlayer.swing) {
            Util.swingHand();
        }
    }
    private void stopAttack() {
        if (!SongPlayer.usePacketsOnlyWhilePlaying) {
            SongPlayer.MC.interactionManager.cancelBlockBreaking();
        }
    }
}