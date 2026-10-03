package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.utils.ProtoEncode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * AvatarWeaponSkinDataNotify (cmd 23200).
 * Generated class carries stale 5.x field numbers (8/14/10), so the payload is
 * hand-encoded per 7.0.0 Deobfuscated.proto:
 * weapon_skin_info_list = 9, record_weapon_skin_id_list = 11, HMNEEIJBHBF = 13.
 */
public class PacketAvatarWeaponSkinDataNotify extends BasePacket {

    /**
     * Far future in BOTH seconds (y8,000,000) and milliseconds (y9999). Truncated to
     * uint32 it still lands in 2081, so every plausible client-side interpretation
     * reads as "never expires".
     */
    private static final long PERMANENT_EXPIRE = 253402300799000L;

    public PacketAvatarWeaponSkinDataNotify(Player player) {
        super(PacketOpcodes.AvatarWeaponSkinDataNotify);

        var owned = new TreeSet<>(player.getWeaponSkinList());
        var infos = new ArrayList<byte[]>();
        var recordIds = new int[owned.size()];

        var avatars = player.getAvatars().getAvatars().values();
        int index = 0;
        for (int skinId : owned) {
            recordIds[index++] = skinId;

            List<Long> guids = new ArrayList<>();
            for (var avatar : avatars) {
                if (avatar.getWeaponSkinId() == skinId) guids.add(avatar.getGuid());
            }
            long[] equipped = new long[guids.size()];
            for (int i = 0; i < equipped.length; i++) {
                equipped[i] = guids.get(i);
            }
            infos.add(ProtoEncode.buildWeaponSkinInfo(skinId, equipped, PERMANENT_EXPIRE));
        }

        long field13 = System.currentTimeMillis() / 1000L;
        byte[] payload = ProtoEncode.buildAvatarWeaponSkinDataNotify(infos, recordIds, field13);
        emu.grasscutter.Grasscutter.getLogger()
                .debug("AvatarWeaponSkinDataNotify: ids={} payload={}b", recordIds.length, payload.length);
        this.setData(payload);
    }
}
