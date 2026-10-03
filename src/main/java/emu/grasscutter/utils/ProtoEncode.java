package emu.grasscutter.utils;

import com.google.gson.JsonObject;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import emu.grasscutter.game.player.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled protobuf encoders for the 7.0 profile picture / head frame packets.
 * The 7.0 obfuscated messages (BGEIAEHGKNP, IBIBOHHJJJB, SetPlayerHeadFrameReq/Rsp)
 * have no generated classes (protoc artifact cannot run on this platform), so their
 * wire format is encoded manually. Field numbers per Deobfuscated.proto 7.0.0.
 */
public final class ProtoEncode {

    // ProfileFrameExcelConfigData.json only has 5 rows (old resources), but the 7.0 client
    // has a larger catalog (e.g. Snezhnaya frame) that our resources don't contain. The
    // client renders its own catalog and only checks membership in our unlock list, so send
    // a generous id range; unknown ids are ignored client-side.
    public static final int[] ALL_PROFILE_FRAME_IDS = buildFrameIds();

    private static int[] buildFrameIds() {
        int[] ids = new int[10000];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 100000 + i;
        }
        return ids;
    }

    // Picture ids + avatar mapping loaded from resources/ExcelBinOutput/ProfilePictureExcelConfigData.json
    public static final int[] ALL_PROFILE_PICTURE_IDS;
    private static final Map<Integer, Integer> PICTURE_AVATAR_MAP;

    static {
        int[] ids = new int[0];
        Map<Integer, Integer> map = new HashMap<>();
        try {
            List<JsonObject> rows =
                    JsonUtils.loadToList(
                            FileUtils.getResourcePath(
                                    "ExcelBinOutput/ProfilePictureExcelConfigData.json"),
                            JsonObject.class);
            var sorted = new ArrayList<Integer>();
            for (var row : rows) {
                int id = row.get("id").getAsInt();
                sorted.add(id);
                if ("PROFILE_PICTURE_UNLOCK_BY_AVATAR"
                        .equals(row.get("AJFEJFNMCKP").getAsString())) {
                    map.put(id, row.get("unlockParam").getAsInt());
                }
            }
            sorted.sort(null);
            ids = sorted.stream().mapToInt(Integer::intValue).toArray();
        } catch (Exception e) {
            System.err.println("Failed to load ProfilePictureExcelConfigData: " + e);
        }
        ALL_PROFILE_PICTURE_IDS = ids;
        PICTURE_AVATAR_MAP = map;
    }

    private ProtoEncode() {}

    /** Picture id → avatar id (for BY_AVATAR pictures). 0 if unknown. */
    public static int pictureToAvatar(int pictureId) {
        return PICTURE_AVATAR_MAP.getOrDefault(pictureId, 0);
    }

    /** Every picture in the excel is selectable (private server: all unlocked). */
    public static int[] ownedPictureIds(Player player) {
        return ALL_PROFILE_PICTURE_IDS;
    }

    /**
     * BGEIAEHGKNP (cmd 20816) — rsp with owned lists:
     * profile_frame_id_list = 11, profile_picture_id_list = 6, retcode = 10 (0 = success, omitted).
     */
    public static byte[] buildProfileListsRsp(int[] frameIds, int[] pictureIds) {
        try {
            var out = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(out);
            writePackedUInt32(cos, 11, frameIds);
            writePackedUInt32(cos, 6, pictureIds);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * IBIBOHHJJJB (cmd 22090) — notify with owned lists:
     * profile_picture_id_list = 10, profile_frame_id_list = 7.
     */
    public static byte[] buildProfileListsNotify(int[] pictureIds, int[] frameIds) {
        try {
            var out = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(out);
            writePackedUInt32(cos, 10, pictureIds);
            writePackedUInt32(cos, 7, frameIds);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** ProfilePicture: avatar_id = 1, costume_id = 2, profile_picture_id = 3, profile_frame_id = 4. */
    public static byte[] buildProfilePicture(int avatarId, int pictureId, int frameId) {
        try {
            var out = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(out);
            if (avatarId != 0) cos.writeUInt32(1, avatarId);
            if (pictureId != 0) cos.writeUInt32(3, pictureId);
            if (frameId != 0) cos.writeUInt32(4, frameId);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds a {@code ProfilePicture} carrying {@code profile_frame_id} (field 4), which the
     * generated 7.0 class does not expose yet. The raw bytes are re-parsed so the unknown field
     * is preserved when the parent message is serialized.
     */
    public static emu.grasscutter.net.proto.ProfilePictureOuterClass.ProfilePicture toProfilePicture(
            int avatarId, int pictureId, int frameId) {
        try {
            return emu.grasscutter.net.proto.ProfilePictureOuterClass.ProfilePicture.parseFrom(
                    buildProfilePicture(avatarId, pictureId, frameId));
        } catch (IOException e) {
            return emu.grasscutter.net.proto.ProfilePictureOuterClass.ProfilePicture.newBuilder()
                    .setAvatarId(avatarId)
                    .setProfilePictureId(pictureId)
                    .build();
        }
    }

    /**
     * SetPlayerHeadFrameRsp (cmd 29314): profile_picture = 15, retcode = 7 (0 omitted).
     */
    public static byte[] buildSetPlayerHeadFrameRsp(int avatarId, int pictureId, int frameId) {
        try {
            byte[] pic = buildProfilePicture(avatarId, pictureId, frameId);
            var out = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(out);
            cos.writeTag(15, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            cos.writeRawVarint32(pic.length);
            cos.writeRawBytes(pic);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * SetPlayerHeadImageRsp (cmd 24323): profile_picture = 14, retcode = 5 (0 omitted).
     */
    public static byte[] buildSetPlayerHeadImageRsp(int avatarId, int pictureId, int frameId) {
        try {
            byte[] pic = buildProfilePicture(avatarId, pictureId, frameId);
            var out = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(out);
            cos.writeTag(14, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            cos.writeRawVarint32(pic.length);
            cos.writeRawBytes(pic);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * SetPlayerHeadFrameReq (cmd 4161): FGIKFKJBGPH = field 15 (uint32), the frame id to equip.
     */
    public static int parseHeadFrameId(byte[] payload) {
        try {
            var cis = CodedInputStream.newInstance(payload);
            int tag;
            while ((tag = cis.readTag()) != 0) {
                if (tag == ((15 << 3) | WireFormat.WIRETYPE_VARINT)) {
                    return cis.readUInt32();
                }
                if (!cis.skipField(tag)) break;
            }
        } catch (IOException ignored) {
        }
        return 0;
    }

    private static void writePackedUInt32(CodedOutputStream cos, int field, int[] values)
            throws IOException {
        if (values == null || values.length == 0) return;
        cos.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        int size = 0;
        for (int value : values) {
            size += CodedOutputStream.computeUInt32SizeNoTag(value);
        }
        cos.writeRawVarint32(size);
        for (int value : values) {
            cos.writeUInt32NoTag(value);
        }
    }

    /** Append a raw bool field to an already-serialized message (for obfuscated fields absent from generated classes). */
    public static byte[] appendBool(byte[] base, int field, boolean value) {
        if (!value) return base;
        try {
            var out = new ByteArrayOutputStream(base.length + 8);
            out.write(base);
            var cos = CodedOutputStream.newInstance(out);
            cos.writeBool(field, value);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Append a scalar varint field. Omitted when value is 0 (proto3 default). */
    public static byte[] appendVarint(byte[] base, int field, long value) {
        if (value == 0) return base;
        try {
            var out = new ByteArrayOutputStream(base.length + 12);
            out.write(base);
            var cos = CodedOutputStream.newInstance(out);
            cos.writeUInt64(field, value);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Append an already-serialized submessage as a length-delimited field. */
    public static byte[] appendMessage(byte[] base, int field, byte[] sub) {
        if (sub == null || sub.length == 0) return base;
        try {
            var out = new ByteArrayOutputStream(base.length + sub.length + 8);
            out.write(base);
            var cos = CodedOutputStream.newInstance(out);
            cos.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            cos.writeRawVarint32(sub.length);
            cos.writeRawBytes(sub);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Append a packed repeated uint32 field. */
    public static byte[] appendPackedUInt32(byte[] base, int field, int[] values) {
        if (values == null || values.length == 0) return base;
        try {
            var out = new ByteArrayOutputStream(base.length + values.length * 4 + 8);
            out.write(base);
            var cos = CodedOutputStream.newInstance(out);
            writePackedUInt32(cos, field, values);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Append a repeated uint64 field (one tag per element, never packed by clients for these). */
    public static byte[] appendRepeatedUInt64(byte[] base, int field, List<Long> values) {
        if (values == null || values.isEmpty()) return base;
        try {
            var out = new ByteArrayOutputStream(base.length + values.size() * 10 + 8);
            out.write(base);
            var cos = CodedOutputStream.newInstance(out);
            for (long value : values) cos.writeUInt64(field, value);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static final int CAPTURING_RADIANCE_FIELD = 1619;

    // ------------------------------------------------------------------
    // Weapon skin messages (7.0.0 Deobfuscated.proto field numbers).
    // The generated AvatarWeaponSkin* classes carry stale 5.x field
    // numbers for the data notify, so the notify is encoded by hand.
    // ------------------------------------------------------------------

    /** _WeaponSkinInfo: equipped_avatar_guid_list = 2, weapon_skin_id = 15, expire_time = 3. */
    public static byte[] buildWeaponSkinInfo(int skinId, long[] equippedGuids, long expireTime) {
        try {
            var out = new ByteArrayOutputStream(32);
            var cos = CodedOutputStream.newInstance(out);
            if (equippedGuids != null) {
                for (long guid : equippedGuids) cos.writeUInt64(2, guid);
            }
            cos.writeUInt32(15, skinId);
            if (expireTime != 0) cos.writeUInt64(3, expireTime);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * _AvatarWeaponSkinDataNotify: weapon_skin_info_list = 9,
     * record_weapon_skin_id_list = 11, HMNEEIJBHBF = 13.
     */
    public static byte[] buildAvatarWeaponSkinDataNotify(
            List<byte[]> infoList, int[] recordIds, long something13) {
        try {
            var out = new ByteArrayOutputStream(64);
            var cos = CodedOutputStream.newInstance(out);
            if (infoList != null) {
                for (byte[] info : infoList) {
                    if (info == null || info.length == 0) continue;
                    cos.writeTag(9, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    cos.writeRawVarint32(info.length);
                    cos.writeRawBytes(info);
                }
            }
            writePackedUInt32(cos, 11, recordIds);
            if (something13 != 0) cos.writeUInt64(13, something13);
            cos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
