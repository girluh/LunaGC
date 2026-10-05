package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AvatarWearWeaponSkinRspOuterClass.AvatarWearWeaponSkinRsp;
import emu.grasscutter.net.proto.RetcodeOuterClass;

import java.util.List;

/** AvatarWearWeaponSkinRsp (cmd 22873). Generated field numbers match 7.0.0 (7/3/2). */
public class PacketAvatarWearWeaponSkinRsp extends BasePacket {

    public PacketAvatarWearWeaponSkinRsp(List<Long> avatarGuids, int weaponSkinId) {
        super(PacketOpcodes.AvatarWearWeaponSkinRsp);

        AvatarWearWeaponSkinRsp.Builder proto =
                AvatarWearWeaponSkinRsp.newBuilder().setWeaponSkinId(weaponSkinId);
        if (avatarGuids != null) {
            proto.addAllAvatarGuidList(avatarGuids);
        }

        this.setData(proto);
    }

    public PacketAvatarWearWeaponSkinRsp() {
        super(PacketOpcodes.AvatarWearWeaponSkinRsp);

        AvatarWearWeaponSkinRsp proto =
                AvatarWearWeaponSkinRsp.newBuilder()
                        .setRetcode(RetcodeOuterClass.Retcode.RET_SVR_ERROR_VALUE)
                        .build();

        this.setData(proto);
    }
}
