package emu.grasscutter.game.avatar;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.BasePlayerManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.server.event.entity.EntityCreationEvent;
import emu.grasscutter.server.packet.send.PacketAvatarChangeCostumeNotify;
import emu.grasscutter.server.packet.send.PacketAvatarEquipChangeNotify;
import emu.grasscutter.server.packet.send.PacketAvatarFlycloakChangeNotify;
import emu.grasscutter.server.packet.send.PacketAvatarTraceEffectChangeNotify;
import emu.grasscutter.server.packet.send.PacketAvatarWeaponSkinDataNotify;
import emu.grasscutter.server.packet.send.PacketSceneEntityUpdateNotify;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Iterator;
import java.util.List;

public class AvatarStorage extends BasePlayerManager implements Iterable<Avatar> {
    private final Int2ObjectMap<Avatar> avatars;
    private final Long2ObjectMap<Avatar> avatarsGuid;

    public AvatarStorage(Player player) {
        super(player);
        this.avatars = new Int2ObjectOpenHashMap<>();
        this.avatarsGuid = new Long2ObjectOpenHashMap<>();
    }

    public Int2ObjectMap<Avatar> getAvatars() {
        return avatars;
    }

    public int getAvatarCount() {
        return this.avatars.size();
    }

    public Avatar getAvatarById(int id) {
        return getAvatars().get(id);
    }

    public Avatar getAvatarByGuid(long id) {
        return avatarsGuid.get(id);
    }

    public boolean hasAvatar(int id) {
        return getAvatars().containsKey(id);
    }

    public boolean addAvatar(Avatar avatar) {
        if (avatar.getAvatarData() == null || this.hasAvatar(avatar.getAvatarId())) {
            return false;
        }

        // Set owner first
        avatar.setOwner(getPlayer());

        // Put into maps
        this.avatars.put(avatar.getAvatarId(), avatar);
        this.avatarsGuid.put(avatar.getGuid(), avatar);

        avatar.save();

        return true;
    }

    public void addStartingWeapon(Avatar avatar) {
        // Make sure avatar owner is this player
        if (avatar.getPlayer() != this.getPlayer()) {
            return;
        }

        // Create weapon
        GameItem weapon = new GameItem(avatar.getAvatarData().getInitialWeapon());

        if (weapon.getItemData() != null) {
            this.getPlayer().getInventory().addItem(weapon);

            avatar.equipItem(weapon, true);
        }
    }

    public boolean wearFlycloak(long avatarGuid, int flycloakId) {
        Avatar avatar = this.getAvatarByGuid(avatarGuid);

        if (avatar == null || !getPlayer().getFlyCloakList().contains(flycloakId)) {
            return false;
        }

        avatar.setFlyCloak(flycloakId);
        avatar.save();

        // Update
        getPlayer().sendPacket(new PacketAvatarFlycloakChangeNotify(avatar));

        return true;
    }

    public boolean changeCostume(long avatarGuid, int costumeId) {
        Avatar avatar = this.getAvatarByGuid(avatarGuid);

        if (avatar == null) {
            return false;
        }

        if (costumeId != 0 && !getPlayer().getCostumeList().contains(costumeId)) {
            return false;
        }

        // TODO make sure avatar can wear costume

        avatar.setCostume(costumeId);
        avatar.save();

        // Update entity
        EntityAvatar entity = avatar.getAsEntity();
        if (entity == null) {
            entity =
                    EntityCreationEvent.call(
                            EntityAvatar.class, new Class<?>[] {Avatar.class}, new Object[] {avatar});
            getPlayer().getWorld().broadcastPacket(new PacketAvatarChangeCostumeNotify(entity));
        } else {
            getPlayer().getWorld().broadcastPacket(new PacketAvatarChangeCostumeNotify(entity));
        }

        // Notify costume change to HomeWorld
        this.getPlayer().getHome().onPlayerChangedAvatarCostume(avatar);

        // Done
        return true;
    }
    public boolean changeTraceEffect(long avatarGuid, int traceEffectId) {
        Avatar avatar = this.getAvatarByGuid(avatarGuid);
        if (avatar == null || !this.getPlayer().getTraceEffectList().contains(traceEffectId) && traceEffectId != 0) {
            return false;
        }
        avatar.setTraceEffect(traceEffectId);
        avatar.save();
        EntityAvatar entity = avatar.getAsEntity();
        if (entity == null) {
            entity =
                    EntityCreationEvent.call(
                            EntityAvatar.class, new Class<?>[] {Avatar.class}, new Object[] {avatar});
            getPlayer().getWorld().broadcastPacket(new PacketAvatarTraceEffectChangeNotify(entity));
        } else {
            getPlayer().getWorld().broadcastPacket(new PacketAvatarTraceEffectChangeNotify(entity));
        } 
        return true;
    }

    /**
     * Equips a weapon skin on the given avatars (weaponSkinId 0 unequips). The skin must already
     * be unlocked on the player. Re-sends the avatar entity so the scene re-renders the weapon.
     */
    public boolean changeWeaponSkin(List<Long> avatarGuids, int weaponSkinId) {
        if (avatarGuids == null || avatarGuids.isEmpty()) return false;

        Player player = this.getPlayer();
        if (!player.hasWeaponSkin(weaponSkinId)) return false;

        boolean found = false;
        for (long guid : avatarGuids) {
            Avatar avatar = this.getAvatarByGuid(guid);
            if (avatar == null) continue;
            found = true;

            if (avatar.getWeaponSkinId() == weaponSkinId) continue;

            avatar.setWeaponSkinId(weaponSkinId);
            avatar.save();

            // The world model is refreshed by the weapon equip notify (it carries the skin in
            // SceneWeaponInfo), SceneEntityUpdateNotify alone is not enough for an instant swap.
            GameItem weapon = avatar.getWeapon();
            if (weapon != null) {
                player.sendPacket(new PacketAvatarEquipChangeNotify(avatar, weapon));
            }

            EntityAvatar entity = avatar.getAsEntity();
            if (entity != null && player.getWorld() != null) {
                player.getWorld().broadcastPacket(new PacketSceneEntityUpdateNotify(entity));
            }
        }

        if (found) {
            player.sendPacket(new PacketAvatarWeaponSkinDataNotify(player));
        }
        return found;
    }


    public void loadFromDatabase() {
        if (this.isLoaded()) return;

        List<Avatar> avatars = DatabaseHelper.getAvatars(getPlayer());

        for (Avatar avatar : avatars) {
            // Should never happen
            if (avatar.getObjectId() == null) {
                continue;
            }

            AvatarData avatarData = GameData.getAvatarDataMap().get(avatar.getAvatarId());
            AvatarSkillDepotData skillDepot =
                    GameData.getAvatarSkillDepotDataMap().get(avatar.getSkillDepotId());
            if (avatarData == null || skillDepot == null) {
                continue;
            }

            // Set ownerships
            avatar.setAvatarData(avatarData);
            avatar.setSkillDepot(skillDepot);
            avatar.setOwner(getPlayer());

            // Force recalc of const boosted skills
            avatar.recalcConstellations();

            // Add to avatar storage
            this.avatars.put(avatar.getAvatarId(), avatar);
            this.avatarsGuid.put(avatar.getGuid(), avatar);

            // Set main character skill depot data, fixes loading with no element every login
            if ((avatar.getAvatarId() == 10000007) || (avatar.getAvatarId() == 10000005)) {
                avatar.setSkillDepot(skillDepot);
                avatar.setSkillDepotData(skillDepot);
                avatar.save();
            }
        }

        this.setLoaded(true);
    }

    public void postLoad() {
        for (Avatar avatar : this) {
            // Weapon check
            if (avatar.getWeapon() == null) {
                this.addStartingWeapon(avatar);
            }
            // Recalc stats
            avatar.recalcStats();
        }
    }

    @Override
    public Iterator<Avatar> iterator() {
        return getAvatars().values().iterator();
    }
}
