package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public class ClientCommonNetworkHandlerMixin {
    @Shadow
    private final ClientConnection connection;
    public ClientCommonNetworkHandlerMixin() {
        connection = null;
    }

    public ClientCommonNetworkHandlerMixin(ClientConnection connection) {
        this.connection = connection;
    }

    @Inject(at = @At("HEAD"), method = "sendPacket", cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        Stage stage = SongHandler.getInstance().stage;
        if (stage == null || !SongPlayer.useNoteblocksWhilePlaying || SongHandler.getInstance().paused) {
            //System.out.println("cancel packet logic");
            return;
        }
        if (packet instanceof PlayerMoveC2SPacket) { //override any movement packets to the stage position, as well as rotation if needed.
            //System.out.println("modify move packet");
            PlayerMoveC2SPacket movePacket = (PlayerMoveC2SPacket) packet;
            if (SongPlayer.rotate) {
                if (Util.lastPitch == Util.pitch && Util.lastYaw == Util.yaw) { //prevent sending rotations if you are already facing that direction
                    ci.cancel();
                } else {
                    connection.send(new PlayerMoveC2SPacket.Full(stage.position.getX() + 0.5, stage.position.getY(), stage.position.getZ() + 0.5, Util.yaw, Util.pitch, true));
                }
            } else {
                if ((movePacket).changesLook()) {
                    connection.send(new PlayerMoveC2SPacket.Full(stage.position.getX() + 0.5, stage.position.getY(), stage.position.getZ() + 0.5, SongPlayer.MC.player.getYaw(), SongPlayer.MC.player.getPitch(), true));
                } else {
                    connection.send(new PlayerMoveC2SPacket.PositionAndOnGround(stage.position.getX() + 0.5, stage.position.getY(), stage.position.getZ() + 0.5, true));
                }
            }
            if (SongPlayer.fakePlayer != null) {
                SongPlayer.fakePlayer.copyStagePosAndPlayerLook();
            }
            ci.cancel();
        } else if (packet instanceof VehicleMoveC2SPacket) { //prevents moving in a boat or whatever while playing
            Util.pauseSongIfNeeded();
            Util.lagBackCounter = 0;
            SongPlayer.addChatMessage("§6Your song has been paused because you were moved away from your stage. Go back to your stage and type §3" + SongPlayer.prefix + "resume");
        } else if (packet instanceof TeleportConfirmC2SPacket) { //prevents lagbacks client side
            Util.lagBackCounter++;
            TeleportConfirmC2SPacket tpacket = (TeleportConfirmC2SPacket) packet;
            SongPlayer.addChatMessage(tpacket.getTeleportId() + " - tpacket ID");
            if (Util.lagBackCounter > 3) {
                Util.pauseSongIfNeeded();
                SongPlayer.addChatMessage("§6Your song has been paused because you were moved away from your stage. Go back to your stage and type §3" + SongPlayer.prefix + "resume");
                Util.lagBackCounter = 0;
            } else {
                ci.cancel();
            }
        } else if (packet instanceof ClientCommandC2SPacket) { //prevents sprinting while playing
            ClientCommandC2SPacket sprinting = (ClientCommandC2SPacket) packet;
            ClientCommandC2SPacket.Mode mode = sprinting.getMode();
            if (mode.equals(ClientCommandC2SPacket.Mode.START_SPRINTING) || mode.equals(ClientCommandC2SPacket.Mode.STOP_SPRINTING)) {
                ci.cancel();
            }
        } else if (packet instanceof HandSwingC2SPacket) {
            if (SongPlayer.fakePlayer != null && !ci.isCancelled()) {
                SongPlayer.fakePlayer.swingHand(SongPlayer.fakePlayer.getActiveHand());
            }
        }/* else if (packet instanceof TeleportConfirmC2SPacket) {
			SongPlayer.addChatMessage("tp c2s packet sent");
		}
		*/
    }
}
