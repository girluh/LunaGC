package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetAllMailResultNotifyOuterClass.GetAllMailResultNotify;
import emu.grasscutter.utils.Utils;
import java.time.Instant;
import java.util.List;

public final class PacketGetAllMailResultNotify extends BasePacket {
    /**
     * @param player The player to fetch the mail for.
     * @param gifts Is the mail for gifts?
     */
    public PacketGetAllMailResultNotify(Player player, boolean gifts) {
        super(PacketOpcodes.GetAllMailResultNotify);

        var packet =
                GetAllMailResultNotify.newBuilder()
                        .setTransaction(player.getUid() + "-" + Utils.getCurrentSeconds() + "-" + 0)
                        .setIsCollected(gifts)
                        // Official capture: both page fields are 1 (JNBNDEPLFHD/JJIOBHDAEFO).
                        // Leaving page_index at 0 makes the client spin on "collecting mail".
                        .setPageIndex(1)
                        .setTotalPageCount(1);

        var inbox = player.getAllMail();
        if (!gifts && inbox.size() > 0) {
            packet.addAllMailList(
                    inbox.stream()
                            // stateValue 1=no attachment, 2=uncollected, 3=collected - all must be sent.
                            .filter(mail -> mail.expireTime > Instant.now().getEpochSecond())
                            .map(mail -> mail.toProto(player))
                            .toList());
        } else {
            // Empty mailbox.
            // TODO: Implement the gift mailbox.
            packet.addAllMailList(List.of());
        }

        var built = packet.build();
        emu.grasscutter.Grasscutter.getLogger()
                .info(
                        "GetAllMailResultNotify: uid={} gifts={} mails={} hex={}",
                        player.getUid(),
                        gifts,
                        built.getMailListCount(),
                        emu.grasscutter.utils.Utils.bytesToHex(built.toByteArray()));
        this.setData(built);
    }
}
