package emu.grasscutter.game.entity;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.game.ability.*;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason;
import emu.grasscutter.net.proto.ChangeHpReasonOuterClass.ChangeHpReason;
import emu.grasscutter.net.proto.FightPropPairOuterClass.FightPropPair;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import emu.grasscutter.scripts.data.controller.EntityController;
import emu.grasscutter.net.proto.DetailAbilityInfoOuterClass.DetailAbilityInfo;
import emu.grasscutter.net.proto.PropChangeDetailInfoOuterClass.PropChangeDetailInfo;
import emu.grasscutter.server.event.entity.*;
import emu.grasscutter.server.packet.send.PacketAvatarFightPropNotify;
import emu.grasscutter.server.packet.send.PacketAvatarFightPropUpdateNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropChangeReasonNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropUpdateNotify;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;

import lombok.*;

import static emu.grasscutter.GameConstants.ENTITY_ID_BIT_SHIFT;

public abstract class GameEntity {
    @Getter private final Scene scene;
    private boolean restrictedFromHealing = false;
    /** Heal-to-bond conversion active (may be active with ratio 0 = healing fully negated). */
    private boolean hpDebtConvertActive = false;
    /** Portion of incoming healing converted into a bond of life; 0 = heals negated while active. */
    private float hpDebtConvertRatio = 0f;

    /** Heal tags excluded from bond-of-life conversion (e.g. burst self-heals). */
    private java.util.Set<String> hpDebtConvertExcludedHealTags = java.util.Set.of();

    // Bond-of-life tag budgets registered by LimitHpDebtsByTagMixin: tag -> total budget,
    // tag -> budget consumed so far, tag -> registering modifier key.
    private final Map<String, Float> hpDebtTagLimits =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Float> hpDebtTagUsage =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> hpDebtTagLimitKeys =
            new java.util.concurrent.ConcurrentHashMap<>();
    @Getter @Setter public int id;
    @Getter @Setter private SpawnDataEntry spawnEntry;
    @Setter private PropChangeDetailInfo propChangeDetailInfo;
    @Getter @Setter private DetailAbilityInfo detailAbilityInfo;

    @Getter @Setter private int campId;
    @Getter @Setter private int campType;

    @Getter @Setter private int blockId;
    @Getter @Setter private int configId;
    @Getter @Setter private int groupId;

    @Getter @Setter private MotionState motionState;
    @Getter @Setter private int lastMoveSceneTimeMs;

    @Getter @Setter private int lastMoveReliableSeq;

    @Getter @Setter private boolean lockHP;
    private boolean limbo;
    private float limboHpThreshold;

    @Setter(AccessLevel.PROTECTED)
    @Getter
    private boolean isDead = false;

    @Getter @Setter private EntityController entityController;
    @Getter private ElementType lastAttackType = ElementType.None;

    @Getter private List<Ability> instancedAbilities = new ArrayList<>();

    @Getter
    private Int2ObjectMap<AbilityModifierController> instancedModifiers =
            new Int2ObjectOpenHashMap<>();

    @Getter private Map<String, Float> globalAbilityValues = new HashMap<>();

    public GameEntity(Scene scene) {
        this.scene = scene;
        this.motionState = MotionState.MotionState_MOTION_NONE;
    }

    public abstract void initAbilities();

    public EntityType getEntityType() {
        return EntityIdType.toEntityType(this.getId() >> ENTITY_ID_BIT_SHIFT);
    }
    public boolean isConvertToHpDebt() {
        return hpDebtConvertActive;
    }

    public float getHpDebtConvertRatio() {
        return hpDebtConvertRatio;
    }

    public java.util.Set<String> getHpDebtConvertExcludedHealTags() {
        return hpDebtConvertExcludedHealTags;
    }

    /** Activates heal-to-bond conversion with the given ratio and excluded heal tags.
     *  Ratio may be 0 (healing fully negated while active, e.g. Arlecchino in combat). */
    public void setHpDebtConvert(float ratio, java.util.Set<String> excludedHealTags) {
        this.hpDebtConvertActive = true;
        this.hpDebtConvertRatio = Math.max(0f, ratio);
        this.hpDebtConvertExcludedHealTags =
                excludedHealTags == null || excludedHealTags.isEmpty()
                        ? java.util.Set.of()
                        : java.util.Set.copyOf(excludedHealTags);
    }

    public void clearHpDebtConvert() {
        if (this.hpDebtConvertActive && Grasscutter.getLogger().isDebugEnabled()) {
            Grasscutter.getLogger().debug("clearHpDebtConvert: entity={}", this.getId());
        }
        this.hpDebtConvertActive = false;
        this.hpDebtConvertRatio = 0f;
        this.hpDebtConvertExcludedHealTags = java.util.Set.of();
    }

    /**
     * Converts an incoming heal into bond of life (official SwitchHealToHPDebts semantics): HP is
     * not restored; the bond grows by ratio * healAmount (ratio 0 = healing fully negated).
     * Returns the bond amount added. No-op returning 0 when conversion is inactive.
     */
    public float convertHealToHpDebt(float healAmount) {
        if (!this.hpDebtConvertActive || healAmount <= 0f) return 0f;

        float add = healAmount * this.hpDebtConvertRatio;
        float curDebt = this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
        float maxDebt = 2f * this.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        float newDebt = Math.min(curDebt + add, maxDebt);
        float added = newDebt - curDebt;
        if (Grasscutter.getLogger().isDebugEnabled()) {
            Grasscutter.getLogger().debug(
                    "convertHealToHpDebt: entity={} heal={} ratio={} debt {}->{}{}",
                    this.getId(), healAmount, this.hpDebtConvertRatio, curDebt, newDebt,
                    added <= 0f ? " (negated/capped)" : "");
        }
        if (added <= 0f) return 0f;

        this.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
        this.broadcastHpDebtPropUpdate();
        this.getScene().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
                this,
                FightProperty.FIGHT_PROP_CUR_HP_DEBTS,
                added,
                PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,
                ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_ADD_ABILITY));
        return added;
    }

    /** Registers a per-tag bond-of-life budget for {@code LimitHpDebtsByTagMixin}. */
    public void registerHpDebtTagLimit(String tag, float amount, String key) {
        if (tag == null || tag.isEmpty() || key == null) return;
        hpDebtTagLimits.put(tag, amount);
        hpDebtTagUsage.put(tag, 0f);
        hpDebtTagLimitKeys.put(tag, key);
    }

    /** Removes a tag budget if it was registered by {@code key}. */
    public void unregisterHpDebtTagLimit(String tag, String key) {
        if (tag == null || key == null) return;
        if (hpDebtTagLimitKeys.remove(tag, key)) {
            hpDebtTagLimits.remove(tag);
            hpDebtTagUsage.remove(tag);
        }
    }

    /** Remaining budget for a tag, or MAX_VALUE when no limit is registered. */
    public float getHpDebtTagRemaining(String tag) {
        if (tag == null || tag.isEmpty()) return Float.MAX_VALUE;
        Float limit = hpDebtTagLimits.get(tag);
        if (limit == null) return Float.MAX_VALUE;
        float usage = hpDebtTagUsage.getOrDefault(tag, 0f);
        return Math.max(0f, limit - usage);
    }

    public void addHpDebtTagUsage(String tag, float amount) {
        if (tag == null || tag.isEmpty() || amount <= 0f) return;
        if (!hpDebtTagUsage.containsKey(tag)) return;
        float usage = hpDebtTagUsage.merge(tag, amount, Float::sum);
        Float limit = hpDebtTagLimits.get(tag);
        if (limit != null && usage > limit) hpDebtTagUsage.put(tag, limit);
    }

    /**
     * When bond of life decreases, shrink every tag budget proportionally so paid debt frees
     * the budget it originally consumed.
     */
    private void rescaleHpDebtTagUsage(float oldDebt, float newDebt) {
        if (hpDebtTagUsage.isEmpty() || oldDebt <= 0f || newDebt >= oldDebt) return;
        float factor = Math.max(0f, newDebt / oldDebt);
        hpDebtTagUsage.replaceAll((tag, usage) -> usage * factor);
    }

    /**
     * Syncs the current bond-of-life value: entity-level update for on-field display plus an
     * avatar-level update for the owner so off-field team UI stays correct too.
     */
    public void broadcastHpDebtPropUpdate() {
        if (this.getScene() == null) return;
        this.getScene()
                .broadcastPacket(
                        new PacketEntityFightPropUpdateNotify(
                                this, FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
        if (this instanceof EntityAvatar avatarEntity && avatarEntity.getPlayer() != null) {
            avatarEntity.getPlayer()
                    .sendPacket(
                            new PacketAvatarFightPropUpdateNotify(
                                    avatarEntity.getAvatar(),
                                    FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
        }
    }

    public float getNyxValue() {
        if (this.getGlobalAbilityValues().containsKey("NyxValue")) {
            return this.getGlobalAbilityValues().get("NyxValue");
        } else {
            Grasscutter.getLogger().info("NyxValue not found");
            return 0f;
        }
    }

    public abstract int getEntityTypeId();

    public World getWorld() {
        return this.getScene().getWorld();
    }
        public boolean isRestrictedFromHealing() {
            return restrictedFromHealing;
        }

        public void setRestrictedFromHealing(boolean restricted) {
            this.restrictedFromHealing = restricted;
        }

    public boolean isAlive() {
        return !this.isDead;
    }
    public LifeState getLifeState() {
        return this.isAlive() ? LifeState.LIFE_ALIVE : LifeState.LIFE_DEAD;
    }

    public abstract Int2FloatMap getFightProperties();

    public abstract Position getPosition();

    public abstract Position getRotation();

    public void setFightProperty(FightProperty prop, float value) {
        if (prop == FightProperty.FIGHT_PROP_CUR_HP_DEBTS) {
            float old = this.getFightProperty(prop);
            this.getFightProperties().put(prop.getId(), value);
            this.rescaleHpDebtTagUsage(old, value);
            return;
        }
        this.getFightProperties().put(prop.getId(), value);
    }

    public void setFightProperty(int id, float value) {
        this.getFightProperties().put(id, value);
    }

    public void addFightProperty(FightProperty prop, float value) {
        if (prop == FightProperty.FIGHT_PROP_CUR_HP_DEBTS) {
            float old = this.getFightProperty(prop);
            this.getFightProperties().put(prop.getId(), old + value);
            this.rescaleHpDebtTagUsage(old, old + value);
            return;
        }
        this.getFightProperties().put(prop.getId(), this.getFightProperty(prop) + value);
    }

    public float getFightProperty(FightProperty prop) {
        return this.getFightProperties().getOrDefault(prop.getId(), 0f);
    }

    public boolean hasFightProperty(FightProperty prop) {
        return this.getFightProperties().containsKey(prop.getId());
    }

    public void addAllFightPropsToEntityInfo(SceneEntityInfo.Builder entityInfo) {
        this.getFightProperties()
                .forEach(
                        (key, value) -> {
                            if (key == 0) return;
                            entityInfo.addFightPropList(
                                    FightPropPair.newBuilder().setPropType(key).setPropValue(value).build());
                        });
    }

    protected void setLimbo(float hpThreshold) {
        limbo = true;
        limboHpThreshold = hpThreshold;
    }
    public GameEntity getTrueOwner() {
    if (this instanceof EntityClientGadget gadget) {
        GameEntity owner = gadget.getScene().getEntityById(gadget.getOwnerEntityId());

        return (owner instanceof EntityClientGadget) ? owner.getTrueOwner() : owner;
    }
    return this;
}

    public void onAddAbilityModifier(AbilityModifier data) {
        if (data.properties == null) {
            return;
        }
        var threshold = data.properties.get("Actor_HpThresholdRatio");
        float hpThresholdRatio = threshold != null ? threshold.get() : 0f;

        if (data.state == AbilityModifier.State.Limbo && hpThresholdRatio > 0.0f) {
            Grasscutter.getLogger().info("Limbo set to " + hpThresholdRatio);
            this.setLimbo(hpThresholdRatio);
        }
    }

    private final Int2ObjectMap<Object2FloatMap<FightProperty>> appliedModifierProps =
            new Int2ObjectOpenHashMap<>();

    public void applyModifierProperties(int instancedModifierId, AbilityModifier data, Ability ability) {
        if (data == null || data.properties == null || data.properties.isEmpty()) return;
        if (this.getFightProperties() == null) return;

        // stacking="Refresh" re-adds the same modifier id; undo the previous grant first
        this.revertModifierProperties(instancedModifierId);

        var applied = new Object2FloatOpenHashMap<FightProperty>();
        for (var entry : data.properties.entrySet()) {
            var prop = ActorProperty.getFightProperty(entry.getKey());
            if (prop == null || entry.getValue() == null) continue;

            float delta = ability != null ? entry.getValue().get(ability) : entry.getValue().get();
            if (delta == 0f) continue;

            this.setFightProperty(prop, this.getFightProperty(prop) + delta);
            applied.put(prop, applied.getOrDefault(prop, 0f) + delta);
        }

        if (applied.isEmpty()) return;
        this.appliedModifierProps.put(instancedModifierId, applied);
        this.broadcastModifierProps(applied);
    }

    public void revertModifierProperties(int instancedModifierId) {
        var applied = this.appliedModifierProps.remove(instancedModifierId);
        if (applied == null || this.getFightProperties() == null) return;

        for (var entry : applied.object2FloatEntrySet()) {
            this.setFightProperty(
                    entry.getKey(), this.getFightProperty(entry.getKey()) - entry.getFloatValue());
        }
        this.broadcastModifierProps(applied);
    }

    private void broadcastModifierProps(Object2FloatMap<FightProperty> applied) {
        if (this.getScene() == null) return;
        for (var prop : applied.keySet()) {
            this.getScene().broadcastPacket(new PacketEntityFightPropUpdateNotify(this, prop));
        }
    }

    protected MotionInfo getMotionInfo() {
        return MotionInfo.newBuilder()
                .setPos(this.getPosition().toProto())
                .setRot(this.getRotation().toProto())
                .setSpeed(Vector.newBuilder())
                .setState(this.getMotionState())
                .build();
    }

    protected void injectIntMotionInfo(SceneEntityInfo.Builder entityInfo) {
        try {
            Position pos = this.getPosition();
            Position rot = this.getRotation();
            if (pos == null || rot == null) return;

            int px = Math.round(pos.getX() * 1000f);
            int py = Math.round(pos.getY() * 1000f);
            int pz = Math.round(pos.getZ() * 1000f);
            int rx = Math.round(rot.getX() * 1000f);
            int ry = Math.round(rot.getY() * 1000f);
            int rz = Math.round(rot.getZ() * 1000f);

            ByteArrayOutputStream posOut = new ByteArrayOutputStream();
            CodedOutputStream posCos = CodedOutputStream.newInstance(posOut);
            posCos.writeInt32(1, px);
            posCos.writeInt32(2, py);
            posCos.writeInt32(3, pz);
            posCos.flush();

            ByteArrayOutputStream rotOut = new ByteArrayOutputStream();
            CodedOutputStream rotCos = CodedOutputStream.newInstance(rotOut);
            rotCos.writeInt32(1, rx);
            rotCos.writeInt32(2, ry);
            rotCos.writeInt32(3, rz);
            rotCos.flush();

            ByteArrayOutputStream msgOut = new ByteArrayOutputStream();
            CodedOutputStream msgCos = CodedOutputStream.newInstance(msgOut);
            msgCos.writeUInt32(1, this.getId());
            msgCos.writeBytes(2, ByteString.copyFrom(posOut.toByteArray()));
            msgCos.writeBytes(3, ByteString.copyFrom(rotOut.toByteArray()));
            msgCos.writeEnum(4, this.getMotionState().getNumber());
            msgCos.flush();

            entityInfo.mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(25, UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFrom(msgOut.toByteArray()))
                        .build())
                    .build());
        } catch (Exception e) {
            Grasscutter.getLogger().error("Failed to inject EntityIntMotionInfo", e);
        }
    }

    public float heal(float amount) {
        return heal(amount, false);
    }

    public float heal(float amount, boolean mute) {
        if (this.getFightProperties() == null) {
            return 0f;
        }

        float toHeal = 0f;
        float toRepay = 0f;
        float curHp = this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        float maxHp = this.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        float curHpDebt = this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);

        if (curHp >= maxHp && curHpDebt <= 0) {
            return 0f;
        }

        toRepay = Math.min(amount, curHpDebt);
        toHeal = Math.min(maxHp - curHp, amount - toRepay);
        this.addFightProperty(FightProperty.FIGHT_PROP_CUR_HP, toHeal);
        this.addFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, -toRepay);

        if (toHeal > 0) {
            this.getScene().broadcastPacket(new PacketEntityFightPropUpdateNotify(this, FightProperty.FIGHT_PROP_CUR_HP));
        }
        if (toRepay > 0) {
            this.broadcastHpDebtPropUpdate();

            if (this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS) > 0) {
                this.getScene().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(this, FightProperty.FIGHT_PROP_CUR_HP_DEBTS, -toRepay,
                                                        mute
                                                                ? PropChangeReason.PropChangeReason_PROP_CHANGE_NONE
                                                                : PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,

                                                        ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY
                ));
            } else {
                this.getScene().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(this, FightProperty.FIGHT_PROP_CUR_HP_DEBTS, -toRepay,
                                                        mute
                                                                ? PropChangeReason.PropChangeReason_PROP_CHANGE_NONE
                                                                : PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,

                                                        ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY_FINISH
                                                       ));
            }
        }

        return toHeal;
    }

    public void damage(float amount) {
        GameEntity ownerEntity = resolveOwnerEntity(this);
        this.damage(amount, 0, ElementType.None);
    }
    private GameEntity resolveOwnerEntity(GameEntity owner) {
        if (owner instanceof EntityClientGadget ownerGadget) {

            GameEntity nextOwner = ownerGadget.getScene().getEntityById(ownerGadget.getOwnerEntityId());
            return resolveOwnerEntity(nextOwner);
        }
        return owner;
    }
      public void addSpecialEnergy(float energy){
       float curSpecialEnergy = getFightProperty(FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY);
       float maxSpecialEnergy = getFightProperty(FightProperty.FIGHT_PROP_MAX_SPECIAL_ENERGY);
       curSpecialEnergy+=energy;
       if (curSpecialEnergy >= maxSpecialEnergy){
            curSpecialEnergy = maxSpecialEnergy;
       }
       setFightProperty(FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY, curSpecialEnergy);
       this.getScene().broadcastPacket(new PacketEntityFightPropUpdateNotify(this, FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY));
    }

    public void clearSpecialEnergy(){
        setFightProperty(FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY, 0);
        this.getScene().broadcastPacket(new PacketEntityFightPropUpdateNotify(this, FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY));
    }

    public void damage(float amount, ElementType attackType) {
        this.damage(amount, 0, attackType);
    }

    public void damage(float amount, int killerId, ElementType attackType) {
        this.damage(amount, 0, attackType, PropChangeReason.PropChangeReason_PROP_CHANGE_NONE, ChangeHpReason.ChangeHpReason_CHANGE_HP_NONE);
    }

    public void damage(float amount, PropChangeReason propChangeReason, ChangeHpReason changeHpReason) {
        this.damage(amount, 0, ElementType.None, propChangeReason, changeHpReason);
    }

    public void damage(float amount, int killerId, ElementType attackType, PropChangeReason propChangeReason, ChangeHpReason changeHpReason) {

        if (this.getFightProperties() == null || !hasFightProperty(FightProperty.FIGHT_PROP_CUR_HP)) {
            return;
        }

        if (this instanceof EntityAvatar) {
            float curHpBefore = getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            var st = Thread.currentThread().getStackTrace();
            Grasscutter.getLogger().info("[DMG] EntityAvatar id={} amount={} curHP={} | {}  {}  {}  {}",
                this.getId(), amount, curHpBefore,
                st.length > 2 ? st[2] : "-",
                st.length > 3 ? st[3] : "-",
                st.length > 4 ? st[4] : "-",
                st.length > 5 ? st[5] : "-");
        }

        EntityDamageEvent event =
                new EntityDamageEvent(this, amount, attackType, this.getScene().getEntityById(killerId));
        event.call();
        if (event.isCanceled()) {
            return;
        }

        float effectiveDamage = 0;
        float curHp = getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        if (limbo) {
            float maxHp = getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            float curRatio = curHp / maxHp;
            if (curRatio > limboHpThreshold) {

                effectiveDamage = event.getDamage();
            }
            if (effectiveDamage >= curHp && limboHpThreshold > .0f) {

                effectiveDamage = curHp - 1;
            }
        } else if (curHp != Float.POSITIVE_INFINITY && !lockHP
                || lockHP && curHp <= event.getDamage()) {
            effectiveDamage = event.getDamage();
        }

        this.addFightProperty(FightProperty.FIGHT_PROP_CUR_HP, -effectiveDamage);

        this.lastAttackType = attackType;
        this.checkIfDead();
        this.runLuaCallbacks(event);

        this.getScene()
                .broadcastPacket(
                        new PacketEntityFightPropUpdateNotify(this, FightProperty.FIGHT_PROP_CUR_HP));

        if (effectiveDamage > 0) {
            GameEntity attacker = this.getScene().getEntityById(killerId);
            ChangeHpReason dmgHpReason;
            if (attacker instanceof EntityAvatar) {
                dmgHpReason = ChangeHpReason.ChangeHpReason_CHANGE_HP_SUB_AVATAR;
            } else if (attacker instanceof EntityMonster) {
                dmgHpReason = ChangeHpReason.ChangeHpReason_CHANGE_HP_SUB_MONSTER;
            } else {
                dmgHpReason = ChangeHpReason.ChangeHpReason_CHANGE_HP_SUB_ABILITY;
            }
            this.getScene().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
                this, FightProperty.FIGHT_PROP_CUR_HP, -effectiveDamage,
                PropChangeReason.PropChangeReason_PROP_CHANGE_NONE, dmgHpReason));
        }

        if (this instanceof EntityAvatar entityAvatar) {
            entityAvatar.getPlayer().sendPacket(
                new PacketAvatarFightPropNotify(entityAvatar.getAvatar()));
        }

        if (this.isDead) {
            this.getScene().killEntity(this, killerId);
        }
    }

    public void checkIfDead() {
        if (this.getFightProperties() == null || !hasFightProperty(FightProperty.FIGHT_PROP_CUR_HP)) {
            return;
        }

        if (this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) <= 0f) {
            this.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 0f);
            float debt = this.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
            if (debt >= 0) {
                this.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, 0f);
                this.broadcastHpDebtPropUpdate();
                this.getScene().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(this, FightProperty.FIGHT_PROP_CUR_HP_DEBTS, -debt, PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY, ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_CLEAR));
            }
            this.isDead = true;
        }
    }

    public void runLuaCallbacks(EntityDamageEvent event) {
        if (entityController != null) {
            entityController.onBeHurt(this, event.getAttackElementType(), true);
        }
    }

    public void move(Position position, Position rotation) {

        this.getPosition().set(position);
        this.getRotation().set(rotation);
    }

    public void onInteract(Player player, GadgetInteractReq interactReq) {}

    public void onCreate() {}

    public void onRemoved() {}

    private int[] parseCountRange(String range) {
        var split = range.split(";");
        if (split.length == 1)
            return new int[] {Integer.parseInt(split[0]), Integer.parseInt(split[0])};
        return new int[] {Integer.parseInt(split[0]), Integer.parseInt(split[1])};
    }

    public boolean dropSubfieldItem(int dropId) {
        var drop = GameData.getDropSubfieldMappingMap().get(dropId);
        if (drop == null) return false;
        var dropTableEntry = GameData.getDropTableExcelConfigDataMap().get(drop.getItemId());
        if (dropTableEntry == null) return false;

        Int2ObjectMap<Integer> itemsToDrop = new Int2ObjectOpenHashMap<>();
        switch (dropTableEntry.getRandomType()) {
            case 0:
                {
                    int weightCount = 0;
                    for (var entry : dropTableEntry.getDropVec()) weightCount += entry.getWeight();

                    int randomValue = new Random().nextInt(weightCount);

                    weightCount = 0;
                    for (var entry : dropTableEntry.getDropVec()) {
                        if (randomValue >= weightCount && randomValue < (weightCount + entry.getWeight())) {
                            var countRange = parseCountRange(entry.getCountRange());
                            itemsToDrop.put(
                                    entry.getItemId(),
                                    Integer.valueOf((new Random().nextBoolean() ? countRange[0] : countRange[1])));
                        }
                    }
                }
                break;
            case 1:
                {
                    for (var entry : dropTableEntry.getDropVec()) {
                        if (entry.getWeight() < new Random().nextInt(10000)) {
                            var countRange = parseCountRange(entry.getCountRange());
                            itemsToDrop.put(
                                    entry.getItemId(),
                                    Integer.valueOf((new Random().nextBoolean() ? countRange[0] : countRange[1])));
                        }
                    }
                }
                break;
        }

        for (var entry : itemsToDrop.int2ObjectEntrySet()) {
            var item =
                    new EntityItem(
                            scene,
                            null,
                            GameData.getItemDataMap().get(entry.getIntKey()),
                            getPosition().nearby2d(1f).addY(0.5f),
                            entry.getValue(),
                            true);

            scene.addEntity(item);
        }

        return true;
    }

    public boolean dropSubfield(String subfieldName) {
        var subfieldMapping = GameData.getSubfieldMappingMap().get(getEntityTypeId());
        if (subfieldMapping == null || subfieldMapping.getSubfields() == null) return false;

        for (var entry : subfieldMapping.getSubfields()) {
            if (entry.getSubfieldName().compareTo(subfieldName) == 0) {
                return dropSubfieldItem(entry.getDrop_id());
            }
        }

        return false;
    }

    public void onTick(int sceneTime) {
        if (entityController != null) {
            entityController.onTimer(this, sceneTime);
        }
    }

    public int onClientExecuteRequest(int param1, int param2, int param3) {
        if (entityController != null) {
            return entityController.onClientExecuteRequest(this, param1, param2, param3);
        }
        return 0;
    }

    public void onDeath(int killerId) {

        EntityDeathEvent event = new EntityDeathEvent(this, killerId);
        event.call();

        if (entityController != null) {
            entityController.onDie(this, getLastAttackType());
        }

        this.isDead = true;
    }

    public void onAbilityValueUpdate() {

    }

    public abstract SceneEntityInfo toProto();

    @Override
    public String toString() {
        return "Entity ID: %s; Group ID: %s; Config ID: %s"
                .formatted(this.getId(), this.getGroupId(), this.getConfigId());
    }
}
