package emu.grasscutter.game.ability;

import com.google.protobuf.*;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.ability.actions.*;
import emu.grasscutter.game.ability.mixins.*;
import emu.grasscutter.game.avatar.Avatar;

import emu.grasscutter.net.proto.AbilityMetaSpecialEnergyOuterClass;
import emu.grasscutter.net.proto.DetailAbilityInfoOuterClass.DetailAbilityInfo;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.data.excels.ProudSkillData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry;
import emu.grasscutter.net.proto.AbilityMetaAddAbilityOuterClass.AbilityMetaAddAbility;
import emu.grasscutter.net.proto.AbilityMetaModifierChangeOuterClass.AbilityMetaModifierChange;
import emu.grasscutter.server.packet.send.PacketMonsterSummonTagNotify;
import emu.grasscutter.net.proto.AbilityMetaReInitOverrideMapOuterClass.AbilityMetaReInitOverrideMap;
import emu.grasscutter.net.proto.AbilityMetaSetKilledStateOuterClass.AbilityMetaSetKilledState;
import emu.grasscutter.net.proto.AbilityScalarTypeOuterClass.AbilityScalarType;
import emu.grasscutter.net.proto.AbilityScalarValueEntryOuterClass.AbilityScalarValueEntry;
import emu.grasscutter.net.proto.ModifierActionOuterClass.ModifierAction;
import emu.grasscutter.server.event.player.PlayerUseSkillEvent;
import emu.grasscutter.net.proto.AbilityStringOuterClass;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.utils.Utils;

import emu.grasscutter.net.proto.AbilityInvokeArgumentOuterClass.AbilityInvokeArgument;
import emu.grasscutter.net.proto.AbilityInvokeEntryHeadOuterClass.AbilityInvokeEntryHead;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass;
import emu.grasscutter.server.packet.send.PacketAbilityInvocationsNotify;
import emu.grasscutter.server.packet.send.PacketAvatarFightPropNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropChangeReasonNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropUpdateNotify;
import emu.grasscutter.server.packet.send.PacketPlayerEnterSceneInfoNotify;
import emu.grasscutter.server.packet.send.PacketServerGlobalValueChangeNotify;
import emu.grasscutter.game.props.*;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.*;
import java.util.concurrent.*;

import lombok.Getter;

public final class AbilityManager extends BasePlayerManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbilityManager.class);

    private static final HashMap<AbilityModifierAction.Type, AbilityActionHandler> actionHandlers =
        new HashMap<>();
    public static final HashMap<AbilityMixinData.Type, AbilityMixinHandler> mixinHandlers =
        new HashMap<>();

    public static final ExecutorService eventExecutor;

    static {
        eventExecutor =
            new ThreadPoolExecutor(
                4,
                4,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(1000),
                FastThreadLocalThread::new,
                new ThreadPoolExecutor.AbortPolicy());

        registerHandlers();
    }

    @Getter
    private boolean abilityInvulnerable = false;
    private int burstCasterId;
    private int burstSkillId;

    private long arlecchinoChargedAttackTime = 0L;
    private long arlecchinoESkillTime = 0L;

    private static final java.util.concurrent.ScheduledExecutorService burstHealScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
                var t = new Thread(r, "burst-heal-timer");
                t.setDaemon(true);
                return t;
            });
    private static final long ARLECCHINO_BURST_HEAL_TIMEOUT_MS = 500L;
    private static final long ARLECCHINO_BURST_HEAL_LOOKBACK_MS = 300L;

    private Runnable pendingArlecchinoBurstHeal;
    private int pendingArlecchinoBurstEntityId;
    private java.util.concurrent.ScheduledFuture<?> pendingArlecchinoBurstHealTimeout;
    private long lastCombatHitAtMs;
    private int lastCombatHitDealerId;

    public AbilityManager(Player player) {
        super(player);
        removePendingEnergyClear();
    }

    public void removePendingEnergyClear() {
        this.burstCasterId = 0;
        this.burstSkillId = 0;
    }

    private void onPossibleElementalBurst(Ability ability, AbilityModifier modifier, int entityId) {

        if (ability == null) {
            Grasscutter.getLogger().trace("possible elemental burst is null");
            return;
        }

        if (this.burstCasterId == 0) return;

        boolean skillInvincibility = modifier.state == AbilityModifier.State.Invincible;
        if (modifier.onAdded != null) {
            skillInvincibility |=
                Arrays.stream(modifier.onAdded)
                    .filter(
                        action ->
                            action.type == AbilityModifierAction.Type.AttachAbilityStateResistance
                                && action.resistanceListID == 11002)
                    .toList()
                    .size()
                    > 0;
        }

        if (this.burstCasterId == entityId
            && (ability.getAvatarSkillStartIds().contains(this.burstSkillId) || skillInvincibility)) {
            Grasscutter.getLogger()
                .trace(
                    "Caster ID's {} burst successful, clearing energy and setting invulnerability",
                    entityId);
            this.abilityInvulnerable = true;

            this.player
                .getEnergyManager()
                .handleEvtDoSkillSuccNotify(
                    this.player.getSession(), this.burstSkillId, this.burstCasterId);

            this.removePendingEnergyClear();
        }
    }

    public static void registerHandlers() {
        var handlerClassesAction = Grasscutter.reflector.getSubTypesOf(AbilityActionHandler.class);

        for (var obj : handlerClassesAction) {
            try {
                if (obj.isAnnotationPresent(AbilityAction.class)) {
                    AbilityModifierAction.Type abilityAction = obj.getAnnotation(AbilityAction.class).value();
                    actionHandlers.put(abilityAction, obj.getDeclaredConstructor().newInstance());
                } else {
                    continue;
                }
            } catch (Exception e) {
                Grasscutter.getLogger().error("Unable to register handler.", e);
            }
        }

        var handlerClassesMixin = Grasscutter.reflector.getSubTypesOf(AbilityMixinHandler.class);
        for (var obj : handlerClassesMixin) {
            try {
                if (obj.isAnnotationPresent(AbilityMixin.class)) {
                    AbilityMixinData.Type abilityMixin = obj.getAnnotation(AbilityMixin.class).value();
                    mixinHandlers.put(abilityMixin, obj.getDeclaredConstructor().newInstance());
                } else {
                    continue;
                }
            } catch (Exception e) {
                Grasscutter.getLogger().error("Unable to register handler.", e);
            }
        }
    }

    public void executeAction(
        Ability ability, AbilityModifierAction action, ByteString abilityData, GameEntity target) {
        var handler = actionHandlers.get(action.type);
        if (handler == null || ability == null) {
            if (DebugConstants.LOG_MISSING_ABILITY_HANDLERS) {
                Grasscutter.getLogger()
                    .debug("Missing ability action handler for {} (invoker: {}).", action.type, ability);
            }

            return;
        }

        eventExecutor.submit(
            () -> {
                if (!handler.execute(ability, action, abilityData, target)) {
                    Grasscutter.getLogger()
                        .debug("Ability execute action failed for {} at {}.", action.type, ability);
                }
            });
    }

    public void executeMixin(Ability ability, AbilityMixinData mixinData, ByteString abilityData) {

        var invoke = AbilityInvokeEntry.newBuilder().setAbilityData(abilityData).build();
        var head = invoke.getHead();
        var handler = mixinHandlers.get(mixinData.type);
        GameEntity target = ability.getOwner();
        Player player = getPlayer();

        if (handler == mixinHandlers.get(AbilityMixinData.Type.PhlogistonCostMixin)) {

            EntityAvatar avatarEntity = player.getTeamManager().getCurrentAvatarEntity();
            Avatar avatar = avatarEntity.getAvatar();
            if (avatar.getAvatarId() == 10000106 || avatar.getAvatarId() == 10000107 || avatar.getAvatarId() == 10000105 || avatar.getAvatarId() == 10000103 || avatar.getAvatarId() == 10000100) {

                Grasscutter.getLogger().trace("NyxValue: " + avatarEntity.getNyxValue());
                float curPhlogiston = player.getPhlogistonValue();
                float consume = 0.67f;
                float updatedPhlogistonValue = curPhlogiston - consume;
                updatedPhlogistonValue = Math.max(0, Math.min(100, updatedPhlogistonValue));
                player.setPhlogistonValue(updatedPhlogistonValue);

                player.sendPacket(new PacketServerGlobalValueChangeNotify(
                    player.getTeamManager().getEntity().getId(),
                    "SGV_PlayerTeam_Phlogiston",
                    updatedPhlogistonValue
                ));

            }
        }

        if (mixinData.type == AbilityMixinData.Type.SwitchHealToHPDebtsMixin) {
            // Client-driven trigger: activate heal-to-bond conversion with the mixin's own
            // ratio and excluded heal tags instead of hardcoding avatar ids.
            this.applySwitchHealToHpDebts(ability, target, mixinData);
        }

        if (handler == null || ability == null) {
            Grasscutter.getLogger()
                .trace("Could not execute ability mixin {} at {}", mixinData.type, ability);
            return;
        }

        eventExecutor.submit(
            () -> {
                if (!handler.execute(ability, mixinData, abilityData, target)) {
                    Grasscutter.getLogger()
                        .error("Ability execute action failed for {} at {}.", mixinData.type, ability);
                }
            });
    }

    public void onAbilityInvoke(AbilityInvokeEntry invoke) throws Exception {
        Grasscutter.getLogger()
            .trace(
                "Ability invoke: "
                    + invoke
                    + " "
                    + invoke.getArgumentType()
                    + " ("
                    + invoke.getArgumentTypeValue()
                    + "): "
                    + this.player.getScene().getEntityById(invoke.getEntityId()));
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        if (entity instanceof EntityAvatar avatarEntity && avatarEntity.getInstancedAbilities().isEmpty()) {
            initializeEntityAbilities(avatarEntity);
        }
        if (entity != null) {
            Grasscutter.getLogger()
                .trace(
                    "Entity {} has a group of {} and a config of {}.",
                    invoke.getEntityId(),
                    entity.getGroupId(),
                    entity.getConfigId());

            Grasscutter.getLogger()
                .trace(
                    "Invoke type of {} ({}) has entity {}.",
                    invoke.getArgumentType(),
                    invoke.getArgumentTypeValue(),
                    entity.getId());
        } else if (DebugConstants.LOG_ABILITIES) {
            Grasscutter.getLogger()
                .debug(
                    "Invoke type of {} ({}) has no entity. (referring to {})",
                    invoke.getArgumentType(),
                    invoke.getArgumentTypeValue(),
                    invoke.getEntityId());
        }

        if (invoke.getHead().getTargetId() != 0) {
            Grasscutter.getLogger()
                .trace("Target: " + this.player.getScene().getEntityById(invoke.getHead().getTargetId()));
        }
        if (invoke.getHead().getLocalId() != 0) {
            this.handleServerInvoke(invoke);
            return;
        }

        switch (invoke.getArgumentType()) {
            case AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM -> this.handleOverrideParam(invoke);

            case AbilityInvokeArgument_ABILITY_META_REINIT_OVERRIDEMAP -> this.handleReinitOverrideMap(invoke);
            case AbilityInvokeArgument_ABILITY_META_MODIFIER_CHANGE -> this.handleModifierChange(invoke);
            case AbilityInvokeArgument_ABILITY_MIXIN_COST_STAMINA -> this.handleMixinCostStamina(invoke);
            case AbilityInvokeArgument_ABILITY_ACTION_GENERATE_ELEM_BALL -> this.handleGenerateElemBall(invoke);
            case AbilityInvokeArgument_ABILITY_META_GLOBAL_FLOAT_VALUE -> this.handleGlobalFloatValue(invoke);
            case AbilityInvokeArgument_ABILITY_META_CLEAR_GLOBAL_FLOAT_VALUE ->
                this.handleClearGlobalFloatValue(invoke);
            case AbilityInvokeArgument_ABILITY_META_MODIFIER_DURABILITY_CHANGE -> this
                .handleModifierDurabilityChange(invoke);
            case AbilityInvokeArgument_ABILITY_META_ADD_NEW_ABILITY -> this.handleAddNewAbility(invoke);

            case AbilityInvokeArgument_ABILITY_META_SET_KILLED_SETATE -> this.handleKillState(invoke);
            case AbilityInvokeArgument_ABILITY_META_ADD_SPECIAL_ENERGY_VALUE -> this.handleAddSpecialEnergy(invoke);

            default -> {
                int typeVal = invoke.getArgumentTypeValue();
                if (typeVal > 100) {
                    Grasscutter.getLogger().trace("UnknownAbilityInvoke: type={} entityId={} dataSize={}",
                        typeVal, invoke.getEntityId(), invoke.getAbilityData().size());
                }
                if (DebugConstants.LOG_MISSING_ABILITIES) {
                    Grasscutter.getLogger()
                        .trace("Missing invoke handler for ability {}.", invoke.getArgumentType().name());
                }
            }
        }
    }

    private void handleClearGlobalFloatValue(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());

        if (entity == null) return;

        var entry = AbilityScalarValueEntry.parseFrom(invoke.getAbilityData());

        if (entry == null) return;
        String key = null;

        if (entry.getKey().hasStr()) {
            key = entry.getKey().getStr();
        } else if (entry.getKey().hasHash()) {
            key = GameData.getAbilityHashes().get(entry.getKey().getHash());
        }

        if (key == null) return;

        entity.getGlobalAbilityValues().remove(key);
        entity.onAbilityValueUpdate();
    }

    private void handleAddSpecialEnergy(AbilityInvokeEntry invoke) throws InvalidProtocolBufferException {
        var head = invoke.getHead();
        AbilityMetaSpecialEnergyOuterClass.AbilityMetaSpecialEnergy abilityMetaSpecialEnergy = AbilityMetaSpecialEnergyOuterClass.AbilityMetaSpecialEnergy.parseFrom(invoke.getAbilityData());
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        if (entity == null) {
            Grasscutter.getLogger().trace("Entity not found: {}", invoke.getEntityId());
            return;
        }
        var target = this.player.getScene().getEntityById(head.getTargetId());
        if (target == null) target = entity;
        float specialEnergyAdd = abilityMetaSpecialEnergy.getValue();
        target.addSpecialEnergy(specialEnergyAdd);
    }

    public void handleServerInvoke(AbilityInvokeEntry invoke) {
        var head = invoke.getHead();

        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        if (entity == null) {
            Grasscutter.getLogger().trace(
                "handleServerInvoke: entity not found: entityId={} localId={} type={}",
                invoke.getEntityId(), head.getLocalId(), invoke.getArgumentType());
            return;
        }

        var target = this.player.getScene().getEntityById(head.getTargetId());
        if (target == null) target = entity;

        Ability ability = null;

        if (head.getInstancedModifierId() != 0
            && entity.getInstancedModifiers().containsKey(head.getInstancedModifierId())) {
            ability = entity.getInstancedModifiers().get(head.getInstancedModifierId()).getAbility();
        }

        if (ability == null
            && head.getInstancedAbilityId() != 0
            && (head.getInstancedAbilityId() - 1) < entity.getInstancedAbilities().size()) {
            ability = entity.getInstancedAbilities().get(head.getInstancedAbilityId() - 1);
        }

        if (ability == null) {
            Grasscutter.getLogger().trace(
                "[InvokeMiss] ability not found: entity={} abilId={} modId={} listSize={}",
                entity.getId(), head.getInstancedAbilityId(), head.getInstancedModifierId(),
                entity.getInstancedAbilities().size());
            return;
        }
        if (ability != null && target != null) {

            var data = ability.getData();

            var detailAbility = DetailAbilityInfo.newBuilder()
                .setParentAbilityName(AbilityString.newBuilder().setStr(data.abilityName))
                .setInstancedAbilityId(head.getInstancedAbilityId())
                .setInstancedModifierId(head.getInstancedModifierId())
                .setLocalId(head.getLocalId())
                .build();

            target.setDetailAbilityInfo(detailAbility);
        }

        var action = ability.getData().localIdToAction.get(head.getLocalId());
        if (action != null) {
            this.executeAction(ability, action, invoke.getAbilityData(), target);
            return;
        } else {
            var mixin = ability.getData().localIdToMixin.get(head.getLocalId());

            if (mixin != null) {
                Grasscutter.getLogger().trace("Executing mixin: {}", mixin);
                executeMixin(ability, mixin, invoke.getAbilityData());

                return;
            }
        }

        Grasscutter.getLogger().trace(
            "handleServerInvoke: action/mixin not found: localId={} ability={} knownActionIds={} knownMixinIds={}",
            head.getLocalId(),
            ability.getData().abilityName,
            ability.getData().localIdToAction.keySet(),
            ability.getData().localIdToMixin.keySet());
    }

    public void onSkillStart(Player player, int skillId, int casterId) {

        if (player.getUid() != this.player.getUid()) {
            return;
        }

        var currentAvatar = player.getTeamManager().getCurrentAvatarEntity();
        if (currentAvatar == null || currentAvatar.getId() != casterId) {
            return;
        }

        if (currentAvatar.getAvatar().getAvatarId() == 10000098) {
            if (skillId == 10982) {

                applyClorindeBoL(currentAvatar, 0.35f);
            } else if (skillId == 10985) {

                applyClorindeBoL(currentAvatar, 0.66f);
            }
        }

        var skillData = GameData.getAvatarSkillDataMap().get(skillId);
        if (skillData == null) {
            return;
        }

        var event = new PlayerUseSkillEvent(player, skillData, currentAvatar.getAvatar());
        if (!event.call()) return;

        if (skillData.getCostElemVal() <= 0) {
            return;
        }

        this.burstSkillId = skillId;
        this.burstCasterId = casterId;
    }

    public void onSkillEnd(Player player) {

        if (player.getUid() != this.player.getUid()) {
            return;
        }

        if (!this.abilityInvulnerable) {
            return;
        }

        this.abilityInvulnerable = false;
    }

    private void setAbilityOverrideValue(Ability ability, AbilityScalarValueEntry valueChange) {
        if (!valueChange.getKey().hasStr()) {
            Grasscutter.getLogger().trace("TODO: Calculate all the ability value hashes");

            return;
        }

        ability.getAbilitySpecials().put(valueChange.getKey().getStr(), valueChange.getFloatValue());
        Grasscutter.getLogger()
            .trace(
                "Ability {} changed {} to {}",
                ability.getData().abilityName,
                valueChange.getKey().getStr(),
                valueChange.getFloatValue());
    }

    private void handleOverrideParam(AbilityInvokeEntry invoke) throws Exception {
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        var head = invoke.getHead();

        if (entity == null) {
            Grasscutter.getLogger().trace("Entity not found: {}", invoke.getEntityId());
            return;
        }

        var instancedAbilityIndex = head.getInstancedAbilityId() - 1;
        if (instancedAbilityIndex < 0 || instancedAbilityIndex >= entity.getInstancedAbilities().size()) {
            Grasscutter.getLogger().trace("Ability not found {}", head.getInstancedAbilityId());
            return;
        }

        var valueChange = AbilityScalarValueEntry.parseFrom(invoke.getAbilityData());

        var ability = entity.getInstancedAbilities().get(instancedAbilityIndex);
        if (ability == null) return;
        setAbilityOverrideValue(ability, valueChange);
    }

    private void handleReinitOverrideMap(AbilityInvokeEntry invoke) throws Exception {
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        var head = invoke.getHead();

        if (entity == null) {
            Grasscutter.getLogger().trace("Entity not found: {}", invoke.getEntityId());
            return;
        }

        var valueChanges = AbilityMetaReInitOverrideMap.parseFrom(invoke.getAbilityData());
        Map<String, Float> computedVarOverrides = new HashMap<>();

        if (entity instanceof EntityClientGadget clientGadget) {
            var ownerEntity = player.getScene().getEntityById(clientGadget.getOriginalOwnerEntityId());
            if (ownerEntity instanceof EntityAvatar avatarOwner) {
                var avatar = avatarOwner.getAvatar();
                AvatarSkillDepotData depot = GameData.getAvatarSkillDepotDataMap().get(avatar.getSkillDepotId());
                List<AbilityInvokeEntry> overrides = new ArrayList<>();

                for (var variableChange : valueChanges.getOverrideMapList()) {
                    String varName = variableChange.getKey().hasStr()
                        ? variableChange.getKey().getStr()
                        : GameData.getAbilityHashes().get(variableChange.getKey().getHash());
                    if (varName == null || varName.isEmpty()) continue;

                    var talentVarList = GameData.getVarNameToTalentVars().get(varName);
                    if (talentVarList == null || talentVarList.isEmpty()) continue;

                    for (var tv : talentVarList) {
                        Integer groupId = GameData.getOpenConfigToProudSkillGroup().get(tv.openConfigName());
                        if (groupId == null) continue;

                        int skillLevel = 1;
                        if (depot != null) {
                            int resolvedGroupId = groupId;
                            skillLevel = depot.getSkillsAndEnergySkill()
                                .filter(sid -> {
                                    var sc = GameData.getAvatarSkillDataMap().get(sid);
                                    return sc != null && sc.getProudSkillGroupId() == resolvedGroupId;
                                })
                                .mapToObj(sid -> avatar.getSkillLevelMap().getOrDefault(sid, 1))
                                .findFirst()
                                .orElse(1);
                        }

                        ProudSkillData proudSkill = GameData.getProudSkillDataMap().get(groupId * 100 + skillLevel);
                        if (proudSkill == null) continue;
                        float[] paramList = proudSkill.getParamList();
                        if (paramList == null || tv.paramIndex() >= paramList.length) continue;

                        float value = paramList[tv.paramIndex()];
                        computedVarOverrides.put(varName, value);
                        overrides.add(AbilityInvokeEntry.newBuilder()
                            .setEntityId(entity.getId())
                            .setArgumentType(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM)
                            .setHead(AbilityInvokeEntryHead.newBuilder()
                                .setInstancedAbilityId(head.getInstancedAbilityId())
                                .build())
                            .setAbilityData(AbilityScalarValueEntry.newBuilder()
                                .setKey(AbilityString.newBuilder()
                                    .setStr(varName)
                                    .setHash(Utils.abilityHash(varName))
                                    .build())
                                .setFloatValue(value)
                                .build()
                                .toByteString())
                            .build());
                        break;
                    }
                }

                if (!overrides.isEmpty()) {

                    player.sendPacket(new PacketAbilityInvocationsNotify(overrides));
                }

                if (!computedVarOverrides.isEmpty()) {

                    var talentVarMap = GameData.getAbilityTalentVarMap();
                    Map<String, Set<String>> varToAbilityNames = new HashMap<>();
                    for (var mapEntry : talentVarMap.entrySet()) {
                        String abilName = mapEntry.getKey();
                        for (var tv : mapEntry.getValue()) {
                            if (computedVarOverrides.containsKey(tv.varName())) {
                                varToAbilityNames
                                    .computeIfAbsent(tv.varName(), k -> new HashSet<>())
                                    .add(abilName);
                            }
                        }
                    }

                    List<AbilityInvokeEntry> avatarPatch = new ArrayList<>();
                    var avatarAbils = avatarOwner.getInstancedAbilities();
                    for (int i = 0; i < avatarAbils.size(); i++) {
                        var ab = avatarAbils.get(i);
                        if (ab == null || ab.getData() == null) continue;
                        String abName = ab.getData().abilityName;
                        if (abName == null) continue;

                        boolean matched = false;
                        for (var e : computedVarOverrides.entrySet()) {
                            Set<String> owners = varToAbilityNames.get(e.getKey());
                            if (owners == null || !owners.contains(abName)) continue;
                            avatarPatch.add(AbilityInvokeEntry.newBuilder()
                                .setEntityId(avatarOwner.getId())
                                .setArgumentType(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM)
                                .setHead(AbilityInvokeEntryHead.newBuilder()
                                    .setInstancedAbilityId(i + 1)
                                    .build())
                                .setAbilityData(AbilityScalarValueEntry.newBuilder()
                                    .setKey(AbilityString.newBuilder()
                                        .setStr(e.getKey())
                                        .setHash(Utils.abilityHash(e.getKey()))
                                        .build())
                                    .setFloatValue(e.getValue())
                                    .build()
                                    .toByteString())
                                .build());
                            ab.getAbilitySpecials().put(e.getKey(), e.getValue());
                            matched = true;
                        }
                        if (matched) {

                        }
                    }
                    if (!avatarPatch.isEmpty()) {
                        player.sendPacket(new PacketAbilityInvocationsNotify(avatarPatch));
                    }
                }
            }
        }

        var instancedAbilityIndex = head.getInstancedAbilityId() - 1;
        if (instancedAbilityIndex < 0 || instancedAbilityIndex >= entity.getInstancedAbilities().size()) {
            Grasscutter.getLogger().trace("Ability not found {}", head.getInstancedAbilityId());
            return;
        }

        var ability = entity.getInstancedAbilities().get(instancedAbilityIndex);
        if (ability != null) {
            for (var variableChange : valueChanges.getOverrideMapList()) {
                setAbilityOverrideValue(ability, variableChange);
            }
        }

        var resolvedAbility = ability;
        if (resolvedAbility == null) {
            for (var ab : entity.getInstancedAbilities()) {
                if (ab != null) { resolvedAbility = ab; break; }
            }
        }
        final var finalAbility = resolvedAbility;
        if (finalAbility != null) {
            computedVarOverrides.forEach((k, v) -> finalAbility.getAbilitySpecials().put(k, v));
        }
    }

    private void handleModifierChange(AbilityInvokeEntry invoke) throws Exception {

        var modChange = AbilityMetaModifierChange.parseFrom(invoke.getAbilityData());
        var head = invoke.getHead();

        boolean isRemove = modChange.getAction() == ModifierAction.MODIFIER_ACTION_REMOVED;
        if ((head.getInstancedAbilityId() == 0 && !isRemove) || head.getInstancedModifierId() > 2000) {
            return;
        }

        if (head.getIsServerbuffModifier()) {

            this.player.getScene().broadcastPacket(new PacketAbilityInvocationsNotify(invoke));
            return;
        }

        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        if (entity == null) {
            if (DebugConstants.LOG_ABILITIES) {
                Grasscutter.getLogger().debug("Entity not found: {}", invoke.getEntityId());
            }

            return;
        }

        if (modChange.getAction() == ModifierAction.MODIFIER_ACTION_ADDED) {
            AbilityData instancedAbilityData = null;
            Ability instancedAbility = null;
            boolean fromParentName = false;

            String resolvedParentName = null;
            var parentAbStr = modChange.getParentAbilityName();
            if (!parentAbStr.getStr().isEmpty()) {
                resolvedParentName = parentAbStr.getStr();
            } else if (parentAbStr.hasHash()) {
                resolvedParentName = GameData.getAbilityHashes().get(parentAbStr.getHash());
            }

            if (resolvedParentName != null) {
                instancedAbilityData = GameData.getAbilityData(resolvedParentName);
                fromParentName = true;
            }

            if (instancedAbilityData == null) {
                if (head.getTargetId() != 0) {
                    var targetEntity = this.player.getScene().getEntityById(head.getTargetId());
                    if (targetEntity != null) {
                        if ((head.getInstancedAbilityId() - 1) < targetEntity.getInstancedAbilities().size()) {
                            instancedAbility = targetEntity.getInstancedAbilities().get(head.getInstancedAbilityId() - 1);
                            if (instancedAbility != null) instancedAbilityData = instancedAbility.getData();
                        }
                    }
                }
            }

            if (instancedAbilityData == null) {
                if ((head.getInstancedAbilityId() - 1) < entity.getInstancedAbilities().size()) {
                    instancedAbility = entity.getInstancedAbilities().get(head.getInstancedAbilityId() - 1);
                    if (instancedAbility != null) instancedAbilityData = instancedAbility.getData();
                }
            }

            var parentAbilityName = resolvedParentName != null ? resolvedParentName : parentAbStr.getStr();

            if (instancedAbilityData == null) {
                Grasscutter.getLogger().trace("handleModifierChange: no ability data found for entityId={} parentAbility={}", invoke.getEntityId(), parentAbilityName);
                return;
            }

            if (instancedAbility == null || fromParentName) {
                instancedAbility = new Ability(instancedAbilityData, entity, player);
            }

            if (instancedAbilityData.modifiers == null) {
                Grasscutter.getLogger().trace("handleModifierChange: modifiers map is null for ability={} entityId={}", instancedAbilityData.abilityName, invoke.getEntityId());
                return;
            }
            var modifierArray = instancedAbilityData.modifiers.values().toArray();
            if (modChange.getModifierLocalId() >= modifierArray.length) {
                Grasscutter.getLogger().trace(
                    "handleModifierChange: modifierLocalId={} out of bounds for ability={} (modifierCount={}), entityId={} modId={}",
                    modChange.getModifierLocalId(), instancedAbilityData.abilityName, modifierArray.length,
                    invoke.getEntityId(), head.getInstancedModifierId());
                return;
            }

            var modifierData = (AbilityModifier) modifierArray[modChange.getModifierLocalId()];
            if (entity.getInstancedModifiers().containsKey(head.getInstancedModifierId())) {
                Grasscutter.getLogger()
                    .trace(
                        "Replacing entity {} modifier id {} with ability {} modifier {}",
                        invoke.getEntityId(),
                        head.getInstancedModifierId(),
                        instancedAbilityData.abilityName,
                        modifierData);
            } else {
                Grasscutter.getLogger()
                    .trace(
                        "Adding entity {} modifier id {} with ability {} modifier {}",
                        invoke.getEntityId(),
                        head.getInstancedModifierId(),
                        instancedAbilityData.abilityName,
                        modifierData);
            }

            if (instancedAbility != null) {
                onPossibleElementalBurst(instancedAbility, modifierData, invoke.getEntityId());
            } else {
                Grasscutter.getLogger().trace("no instanced ability for modifier");
            }

            onPossibleElementalBurst(instancedAbility, modifierData, invoke.getEntityId());

            boolean hasOrchestration = false;
            if (fromParentName && modifierData.onAdded != null) {
                for (var a : modifierData.onAdded) {
                    if (a.type == AbilityModifierAction.Type.AttachModifier
                            || a.type == AbilityModifierAction.Type.ApplyModifier) {
                        hasOrchestration = true;
                        break;
                    }
                }
            }

            if (fromParentName && !hasOrchestration && resolvedParentName != null) {
                outer:
                for (var avatarEntity : this.player.getTeamManager().getActiveTeam()) {
                    if (avatarEntity == entity) continue;
                    for (var a : avatarEntity.getInstancedAbilities()) {
                        if (a != null && a.getData() != null
                                && resolvedParentName.equals(a.getData().abilityName)) {
                            instancedAbility = a;
                            break outer;
                        }
                    }
                }
            }

            AbilityModifierController modifier =
                new AbilityModifierController(instancedAbility, instancedAbilityData, modifierData);

            if (!fromParentName || !hasOrchestration) {
                entity.getInstancedModifiers().put(head.getInstancedModifierId(), modifier);
            }

            entity.applyModifierProperties(
                    head.getInstancedModifierId(), modifierData, instancedAbility);

            // Client-reported modifier attach: register BoL state (heal conversion, tag budgets)
            var boLModifierName = findModifierName(instancedAbility, modifierData);
            if (boLModifierName != null) {
                this.processBoLMixins(entity, instancedAbility, modifierData, boLModifierName);
            }

            if (fromParentName && hasOrchestration && modifierData.onAdded != null) {
                final var finalAbility = instancedAbility;
                final var finalEntity = entity;
                for (var a : modifierData.onAdded) {
                    executeAction(finalAbility, a, invoke.getAbilityData(), finalEntity);
                }
            }
        } else if (modChange.getAction() == ModifierAction.MODIFIER_ACTION_REMOVED) {
            this.cleanupBoLMixinsOnClientRemoval(entity, invoke, modChange);
            entity.revertModifierProperties(head.getInstancedModifierId());
            entity.getInstancedModifiers().remove(head.getInstancedModifierId());
        } else {

            Grasscutter.getLogger().debug("Unknown action");
        }
    }

    /**
     * Client-reported modifier removal: resolve the removed modifier and clear the BoL state it
     * registered so heal-to-bond conversion and tag budgets never outlive their modifier.
     */
    private void cleanupBoLMixinsOnClientRemoval(
            GameEntity entity, AbilityInvokeEntry invoke, AbilityMetaModifierChange modChange) {
        var head = invoke.getHead();

        // 1) Prefer the controller stored when this modifier was added.
        AbilityModifier modifierData = null;
        String modifierName = null;
        String abilityName = null;

        var controller = entity.getInstancedModifiers().get(head.getInstancedModifierId());
        if (controller != null && controller.getModifierData() != null) {
            modifierData = controller.getModifierData();
            if (controller.getAbility() != null && controller.getAbility().getData() != null) {
                abilityName = controller.getAbility().getData().abilityName;
                modifierName = findModifierName(controller.getAbility(), modifierData);
            }
        }

        // 2) Fall back to resolving the modifier from the invoke payload (same rules as ADDED).
        if (modifierData == null || modifierName == null || abilityName == null) {
            String parentName = null;
            var parentAbStr = modChange.getParentAbilityName();
            if (!parentAbStr.getStr().isEmpty()) {
                parentName = parentAbStr.getStr();
            } else if (parentAbStr.hasHash()) {
                parentName = GameData.getAbilityHashes().get(parentAbStr.getHash());
            }

            AbilityData data = parentName != null ? GameData.getAbilityData(parentName) : null;
            if (data == null
                    && head.getInstancedAbilityId() != 0
                    && head.getInstancedAbilityId() - 1 < entity.getInstancedAbilities().size()) {
                var instanced = entity.getInstancedAbilities().get(head.getInstancedAbilityId() - 1);
                if (instanced != null) data = instanced.getData();
            }

            if (data == null || data.modifiers == null) {
                Grasscutter.getLogger().trace(
                    "cleanupBoLMixinsOnClientRemoval: cannot resolve modifier for entity {} localId {}",
                    entity.getId(), modChange.getModifierLocalId());
                return;
            }

            var entries = data.modifiers.entrySet().toArray();
            int localId = modChange.getModifierLocalId();
            if (localId < 0 || localId >= entries.length) return;
            @SuppressWarnings("unchecked")
            var entry = (java.util.Map.Entry<String, AbilityModifier>) entries[localId];
            modifierData = entry.getValue();
            modifierName = entry.getKey();
            abilityName = data.abilityName;
        }

        if (modifierData == null || modifierName == null || abilityName == null) return;
        this.cleanupBoLMixins(entity, modifierData, attachKey(entity, abilityName, modifierName));
    }

    private void handleMixinCostStamina(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
    }

    private void handleGenerateElemBall(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
    }

    private void handleGlobalFloatValue(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());
        if (entity == null) return;

        var entry = AbilityScalarValueEntry.parseFrom(invoke.getAbilityData());
        if (entry == null) return;

        String key = null;
        if (entry.getKey().hasStr()) {
            key = entry.getKey().getStr();
        } else if (entry.getKey().hasHash()) {
            key = GameData.getAbilityHashes().get(entry.getKey().getHash());
        }

        if (key == null) return;
        if (key.startsWith("SGV_")) return;

        float value = entry.getFloatValue();
        if (Float.isNaN(value)) return;

        if (key.equals("MoonOvergrowPoint_All")) {
            value = 50f;
            this.player.sendPacket(new PacketServerGlobalValueChangeNotify(entity, key, 50f));
        }

        if ("_ABILITY_Clorinde_Dodge_HealFlag".equals(key) && value == 0f
                && entity instanceof EntityAvatar clorinde
                && clorinde.getAvatar().getAvatarId() == 10000098) {
            applyClorindeBoL(clorinde);
        }

        if ("_Arlecchino_HPDebtsMark_Level".equals(key)) {
            Float prev = entity.getGlobalAbilityValues().get(key);
            if (value == 0f && prev != null && prev > 0f) {

                boolean isChargedAttack = arlecchinoChargedAttackTime > arlecchinoESkillTime
                    && (System.currentTimeMillis() - arlecchinoChargedAttackTime) < 5000L;
                if (isChargedAttack) {
                    for (var teamMember : this.player.getTeamManager().getActiveTeam()) {
                        if (teamMember.getAvatar().getAvatarId() == 10000096) {
                            applyArlecchinoBoL(teamMember, prev.intValue());
                            break;
                        }
                    }
                }
            }
        }

        entity.getGlobalAbilityValues().put(key, value);
        entity.onAbilityValueUpdate();
    }

    private void applyClorindeBoL(EntityAvatar clorinde) {
        applyClorindeBoL(clorinde, 0.35f);
    }

    private void applyClorindeBoL(EntityAvatar clorinde, float ratio) {
        float maxHp = clorinde.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        float debtAmount = ratio * maxHp;
        float curDebt = clorinde.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
        float newDebt = Math.min(curDebt + debtAmount, 2f * maxHp);
        float change = newDebt - curDebt;
        if (change <= 0f) return;
        clorinde.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
        var scene = this.player.getScene();
        clorinde.broadcastHpDebtPropUpdate();
        scene.broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
            clorinde,
            FightProperty.FIGHT_PROP_CUR_HP_DEBTS,
            change,
            PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,
            ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_ADD_ABILITY
        ));
    }

    private float getArlecchinoBoLRatio(Avatar avatar, String varName) {
        var depot = GameData.getAvatarSkillDepotDataMap().get(avatar.getSkillDepotId());
        if (depot == null) return 0f;
        var skillLevelMap = avatar.getSkillLevelMap();
        var result = new float[]{0f};

        depot.getSkillsAndEnergySkill().forEach(skillId -> {
            if (result[0] > 0f) return;
            var skillData = GameData.getAvatarSkillDataMap().get(skillId);
            if (skillData == null || skillData.getProudSkillGroupId() == 0) return;
            int level = skillLevelMap.getOrDefault(skillId, 1);
            var proudSkill = GameData.getProudSkillDataMap().get(skillData.getProudSkillGroupId() * 100 + level);
            if (proudSkill == null || proudSkill.getOpenConfig() == null) return;
            var entry = GameData.getOpenConfigEntries().get(proudSkill.getOpenConfig());
            if (entry == null || entry.getAbilityVarSetters() == null) return;
            float[] params = proudSkill.getParamList();
            if (params == null) return;
            for (var setter : entry.getAbilityVarSetters()) {
                if (!varName.equals(setter.getVarName())) continue;
                int idx = setter.getParamIndex();
                if (idx < params.length) { result[0] = params[idx]; return; }
            }
        });
        if (result[0] > 0f) return result[0];

        if (depot.getInherentProudSkillOpens() != null) {
            for (var inherent : depot.getInherentProudSkillOpens()) {
                if (result[0] > 0f) break;
                if (inherent.getProudSkillGroupId() == 0) continue;
                var proudSkill = GameData.getProudSkillDataMap().get(inherent.getProudSkillGroupId() * 100 + 1);
                if (proudSkill == null || proudSkill.getOpenConfig() == null) continue;
                var entry = GameData.getOpenConfigEntries().get(proudSkill.getOpenConfig());
                if (entry == null || entry.getAbilityVarSetters() == null) continue;
                float[] params = proudSkill.getParamList();
                if (params == null) continue;
                for (var setter : entry.getAbilityVarSetters()) {
                    if (!varName.equals(setter.getVarName())) continue;
                    int idx = setter.getParamIndex();
                    if (idx < params.length) { result[0] = params[idx]; break; }
                }
            }
        }
        return result[0];
    }

    /** Applies SwitchHealToHPDebtsMixin state (conversion ratio + excluded heal tags). */
    private void applySwitchHealToHpDebts(Ability ability, GameEntity target, AbilityMixinData mixinData) {
        if (target == null || ability == null || mixinData == null) return;
        float ratio = mixinData.ratio != null ? mixinData.ratio.get(ability) : 0f;
        target.setHpDebtConvert(ratio, parseExcludedHealTags(mixinData.predicates));
        if (Grasscutter.getLogger().isDebugEnabled()) {
            Grasscutter.getLogger().debug(
                "SwitchHealToHPDebts: entity={} ratio={} excluded={}",
                target.getId(), ratio, target.getHpDebtConvertExcludedHealTags());
        }
    }

    /**
     * Extracts heal tags excluded from conversion: predicates of the form
     * ByNot { __exp_ByHealTags { __exp_healTags: [...] } } mean those tags are NOT converted.
     */
    private static java.util.Set<String> parseExcludedHealTags(java.util.List<Object> predicates) {
        if (predicates == null || predicates.isEmpty()) return java.util.Set.of();
        var excluded = new java.util.HashSet<String>();
        for (Object predicate : predicates) {
            if (!(predicate instanceof java.util.Map<?, ?> map)) continue;
            if (!"ByNot".equals(String.valueOf(map.get("$type")))) continue;
            if (!(map.get("predicates") instanceof java.util.List<?> inner)) continue;
            for (Object exp : inner) {
                if (!(exp instanceof java.util.Map<?, ?> expMap)) continue;
                if (!"__exp_ByHealTags".equals(String.valueOf(expMap.get("$type")))) continue;
                if (!(expMap.get("__exp_healTags") instanceof java.util.List<?> tags)) continue;
                for (Object tag : tags) {
                    if (tag != null && !tag.toString().isEmpty()) excluded.add(tag.toString());
                }
            }
        }
        return excluded;
    }

    /** Resolves LimitHpDebtsByTagMixin's budget as maxRatio * target max HP. */
    private static float resolveDebtTagLimitAmount(Ability ability, GameEntity entity, AbilityMixinData mixin) {
        float ratio = mixin.debtMaxRatio != null ? mixin.debtMaxRatio.get(ability) : 0f;
        if (ratio <= 0f) return 0f;
        float maxHp = entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        return ratio * maxHp;
    }

    /** Registers BoL-related modifier state (conversion + per-tag debt budgets) on attach. */
    private void processBoLMixins(GameEntity entity, Ability ability, AbilityModifier modifierData, String modifierName) {
        if (entity == null || ability == null || modifierData == null || modifierData.modifierMixins == null) return;

        for (var mixin : modifierData.modifierMixins) {
            if (mixin == null || mixin.type == null) continue;
            switch (mixin.type) {
                case SwitchHealToHPDebtsMixin -> this.applySwitchHealToHpDebts(ability, entity, mixin);
                case LimitHpDebtsByTagMixin -> {
                    if (mixin.debtTags == null) break;
                    float amount = resolveDebtTagLimitAmount(ability, entity, mixin);
                    if (amount <= 0f) {
                        Grasscutter.getLogger().debug(
                            "LimitHpDebtsByTagMixin budget unresolved for {}, skipping", modifierName);
                        break;
                    }
                    String key = attachKey(entity, ability, modifierName);
                    for (String tag : mixin.debtTags) {
                        if (tag == null || tag.isEmpty()) continue;
                        entity.registerHpDebtTagLimit(tag, amount, key);
                        Grasscutter.getLogger().debug(
                            "Registered bond tag limit {}={} for entity {}", tag, amount, entity.getId());
                    }
                }
                default -> {}
            }
        }
    }

    /** Clears BoL-related modifier state when the modifier is removed. */
    private void cleanupBoLMixins(GameEntity entity, AbilityModifier modifierData, String attachKeyValue) {
        if (entity == null || modifierData == null || modifierData.modifierMixins == null) return;

        for (var mixin : modifierData.modifierMixins) {
            if (mixin == null || mixin.type == null) continue;
            switch (mixin.type) {
                case SwitchHealToHPDebtsMixin -> entity.clearHpDebtConvert();
                case LimitHpDebtsByTagMixin -> {
                    if (mixin.debtTags == null) break;
                    for (String tag : mixin.debtTags) entity.unregisterHpDebtTagLimit(tag, attachKeyValue);
                }
                default -> {}
            }
        }
    }

    private static String findModifierName(Ability ability, AbilityModifier data) {
        if (ability == null || ability.getData() == null || ability.getData().modifiers == null) return null;
        for (var entry : ability.getData().modifiers.entrySet()) {
            if (entry.getValue() == data) return entry.getKey();
        }
        return null;
    }

    public void onArlecchinoSkillNotify(int skillId) {
        if (skillId == 10961) {
            arlecchinoChargedAttackTime = System.currentTimeMillis();
        } else if (skillId == 10962) {
            arlecchinoESkillTime = System.currentTimeMillis();
        }
    }

    /**
     * Arlecchino burst heal: officially her bond is consumed when the burst damage lands.
     * Queues the heal until a hit from the same avatar arrives ({@link #onCombatBeingHit}) or a
     * 0.5s timeout fires (HealDelay duration; covers bursts that hit nothing).
     */
    public synchronized void queueArlecchinoBurstHeal(int entityId, Runnable heal) {
        flushArlecchinoBurstHealLocked("REPLACED");
        this.pendingArlecchinoBurstHeal = heal;
        this.pendingArlecchinoBurstEntityId = entityId;
        // The client sends the damage hit invoke ~7ms BEFORE the HealHP invoke; if the matching
        // hit already arrived, fire now instead of waiting for a hit that will never come.
        if (this.lastCombatHitDealerId == entityId
                && System.currentTimeMillis() - this.lastCombatHitAtMs <= ARLECCHINO_BURST_HEAL_LOOKBACK_MS) {
            Grasscutter.getLogger()
                .debug("Arlecchino burst heal queued: entity={} (hit lookback, firing now)", entityId);
            flushArlecchinoBurstHealLocked("HIT_LOOKBACK");
            return;
        }
        this.pendingArlecchinoBurstHealTimeout =
            burstHealScheduler.schedule(
                () -> flushArlecchinoBurstHeal("TIMEOUT"),
                ARLECCHINO_BURST_HEAL_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        Grasscutter.getLogger()
            .debug("Arlecchino burst heal queued: entity={} timeoutMs={}", entityId, ARLECCHINO_BURST_HEAL_TIMEOUT_MS);
    }

    /** Called on combat being-hit invokes; fires a queued burst heal for the hitting avatar. */
    public void onCombatBeingHit(int attackerId) {
        int dealerId = resolveCombatDealer(attackerId);
        synchronized (this) {
            this.lastCombatHitAtMs = System.currentTimeMillis();
            this.lastCombatHitDealerId = dealerId;
            if (Grasscutter.getLogger().isDebugEnabled()) {
                Grasscutter.getLogger().debug(
                    "onCombatBeingHit: attacker={} dealer={} pending={} pendingEntity={}",
                    attackerId, dealerId, this.pendingArlecchinoBurstHeal != null,
                    this.pendingArlecchinoBurstEntityId);
            }
            if (this.pendingArlecchinoBurstHeal == null || this.pendingArlecchinoBurstEntityId != dealerId) {
                return;
            }
            flushArlecchinoBurstHealLocked("HIT");
        }
    }

    /** Q damage can be dealt by a client gadget spawned by the avatar; map it back to the owner. */
    private int resolveCombatDealer(int attackerId) {
        try {
            var scene = getPlayer().getScene();
            if (scene == null) return attackerId;
            var e = scene.getEntityById(attackerId);
            if (e instanceof EntityClientGadget gadget) {
                int owner = gadget.getOwnerEntityId();
                if (owner == 0) owner = gadget.getOriginalOwnerEntityId();
                if (owner != 0) return owner;
            }
        } catch (Exception ignored) {
        }
        return attackerId;
    }

    private void flushArlecchinoBurstHeal(String reason) {
        synchronized (this) {
            flushArlecchinoBurstHealLocked(reason);
        }
    }

    private void flushArlecchinoBurstHealLocked(String reason) {
        var pending = this.pendingArlecchinoBurstHeal;
        if (pending == null) return;
        int entityId = this.pendingArlecchinoBurstEntityId;
        this.pendingArlecchinoBurstHeal = null;
        this.pendingArlecchinoBurstEntityId = 0;
        if (this.pendingArlecchinoBurstHealTimeout != null) {
            this.pendingArlecchinoBurstHealTimeout.cancel(false);
            this.pendingArlecchinoBurstHealTimeout = null;
        }
        Grasscutter.getLogger().debug("Arlecchino burst heal fired ({}): entity={}", reason, entityId);
        eventExecutor.submit(pending);
    }

    private void applyArlecchinoBoL(EntityAvatar arlecchino, int markLevel) {
        String varName = markLevel >= 2 ? "HpDebts_Level_2_Ratio" : "HpDebts_Level_1_Ratio";
        float ratio = getArlecchinoBoLRatio(arlecchino.getAvatar(), varName);
        if (ratio <= 0f) return;

        float maxHp = arlecchino.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        float debt = ratio * maxHp;
        float curDebt = arlecchino.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
        float newDebt = Math.min(curDebt + debt, 2f * maxHp);
        float change = newDebt - curDebt;
        if (change <= 0f) return;

        arlecchino.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
        var scene = this.player.getScene();
        arlecchino.broadcastHpDebtPropUpdate();
        scene.broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
            arlecchino,
            FightProperty.FIGHT_PROP_CUR_HP_DEBTS,
            change,
            PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,
            ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_ADD_ABILITY
        ));
    }

    private void addAbilityByHash(EntityAvatar entity, int hash) {
        var name = GameData.getAbilityHashes().get(hash);
        var data = name != null ? GameData.getAbilityData(name) : null;
        entity.getInstancedAbilities().add(data != null ? new Ability(data, entity, player) : null);
    }

    private static final int MAX_ATTACH_DEPTH = 8;

    // guards re-entry: executeAction dispatches asynchronously, so a depth counter cannot
    // bound recursion through ActionAttachModifier. A modifier stays attached until detached.
    private final java.util.Set<String> attachedModifiers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String attachKey(GameEntity entity, Ability ability, String modifierName) {
        return attachKey(entity, ability.getData().abilityName, modifierName);
    }

    private static String attachKey(GameEntity entity, String abilityName, String modifierName) {
        return entity.getId() + "/" + abilityName + "/" + modifierName;
    }

    public static int syntheticModifierId(String abilityName, String modifierName) {
        return -Math.abs((abilityName + "/" + modifierName).hashCode());
    }

    /** Server-side modifier attach: applies properties, runs onAdded, then follows the modifier's own mixins. */
    public void attachModifier(
            GameEntity entity, Ability ability, String modifierName, ByteString abilityData, int depth) {
        if (entity == null || ability == null || modifierName == null || modifierName.isEmpty()) return;
        if (depth > MAX_ATTACH_DEPTH) return;
        if (ability.getData() == null || ability.getData().modifiers == null) return;

        var modifierData = ability.getData().modifiers.get(modifierName);
        if (modifierData == null) return;

        if (!this.attachedModifiers.add(attachKey(entity, ability, modifierName))) return;

        entity.applyModifierProperties(
                syntheticModifierId(ability.getData().abilityName, modifierName), modifierData, ability);

        if (modifierData.onAdded != null) {
            for (var action : modifierData.onAdded) {
                if (action != null) this.executeAction(ability, action, abilityData, entity);
            }
        }

        this.processModifierMixins(entity, ability, modifierData, abilityData, depth);
        this.processBoLMixins(entity, ability, modifierData, modifierName);
    }

    public void detachModifier(GameEntity entity, Ability ability, String modifierName) {
        if (entity == null || ability == null || ability.getData() == null) return;
        if (!this.attachedModifiers.remove(attachKey(entity, ability, modifierName))) return;
        entity.revertModifierProperties(
                syntheticModifierId(ability.getData().abilityName, modifierName));
        this.cleanupBoLMixins(entity, ability.getData().modifiers != null
                ? ability.getData().modifiers.get(modifierName)
                : null, attachKey(entity, ability, modifierName));
    }

    private void processModifierMixins(
            GameEntity entity,
            Ability ability,
            AbilityModifier modifierData,
            ByteString abilityData,
            int depth) {
        if (modifierData.modifierMixins == null) return;

        for (var mixin : modifierData.modifierMixins) {
            if (mixin == null || mixin.type == null) continue;

            switch (mixin.type) {
                case AttachModifierToGlobalValueMixin -> this.evaluateGlobalValueMixin(
                        entity, ability, mixin, abilityData, depth);
                case CurLocalAvatarMixinV2, CurLocalAvatarMixin -> {
                    var avatar = this.player.getTeamManager().getCurrentAvatarEntity();
                    if (avatar != null
                            && mixin.modifierName != null
                            && !mixin.modifierName.isJsonNull()) {
                        for (var name : mixin.getModifierNames()) {
                            this.attachModifier(avatar, ability, name, abilityData, depth + 1);
                        }
                    }
                }
                default -> {}
            }
        }
    }

    /** Attaches whichever modifierNameStep the watched global value currently selects. */
    public void evaluateGlobalValueMixin(
            GameEntity entity,
            Ability ability,
            AbilityMixinData mixin,
            ByteString abilityData,
            int depth) {
        if (mixin.globalValueKey == null || mixin.modifierNameSteps.isEmpty()) return;

        var source = this.resolveGlobalValueSource(entity, mixin.globalValueTarget);
        if (source == null) return;

        float value =
                source.getGlobalAbilityValues()
                        .getOrDefault(
                                mixin.globalValueKey, mixin.defaultGlobalValueOnCreate.get(ability));

        int step = -1;
        if (mixin.valueSteps.isEmpty()) {
            step = value > 0 ? 0 : -1;
        } else {
            for (int i = 0; i < mixin.valueSteps.size(); i++) {
                float lower = mixin.valueSteps.get(i).get();
                float upper =
                        (i + 1) < mixin.valueSteps.size()
                                ? mixin.valueSteps.get(i + 1).get()
                                : Float.MAX_VALUE;
                if (value >= lower && value < upper) {
                    step = i;
                    break;
                }
            }
        }

        for (int i = 0; i < mixin.modifierNameSteps.size(); i++) {
            var name = mixin.modifierNameSteps.get(i);
            if (name == null || name.isEmpty()) continue;

            if (i == step) {
                this.attachModifier(entity, ability, name, abilityData, depth + 1);
            } else if (mixin.removeAppliedModifier) {
                this.detachModifier(entity, ability, name);
            }
        }
    }

    private GameEntity resolveGlobalValueSource(GameEntity entity, String target) {
        if (target == null) return entity;
        return switch (target) {
            case "Team" -> this.player.getTeamManager().getEntity();
            case "CurLocalAvatar" -> this.player.getTeamManager().getCurrentAvatarEntity();
            default -> entity;
        };
    }

    /**
     * Re-runs global-value watchers that act as roots (RegisterOnce mixins). A modifier is only a
     * root when nothing else in the ability attaches it - otherwise its watcher would fire without
     * the parent that is supposed to gate it.
     */
    public void refreshGlobalValueWatchers(GameEntity entity) {
        if (entity == null) return;
        for (var ability : entity.getInstancedAbilities()) {
            if (ability == null || ability.getData() == null || ability.getData().modifiers == null)
                continue;

            var attachedByOthers = this.collectReferencedModifiers(ability);

            for (var namedModifier : ability.getData().modifiers.entrySet()) {
                var modifierData = namedModifier.getValue();
                if (modifierData == null || modifierData.modifierMixins == null) continue;
                if (attachedByOthers.contains(namedModifier.getKey())) continue;

                for (var mixin : modifierData.modifierMixins) {
                    if (mixin != null
                            && mixin.type == AbilityMixinData.Type.AttachModifierToGlobalValueMixin) {
                        this.evaluateGlobalValueMixin(
                                entity, ability, mixin, ByteString.EMPTY, 0);
                    }
                }
            }
        }
    }

    /** Modifier names that some other modifier in this ability attaches or steps to. */
    private java.util.Set<String> collectReferencedModifiers(Ability ability) {
        var referenced = new java.util.HashSet<String>();
        for (var modifierData : ability.getData().modifiers.values()) {
            if (modifierData == null) continue;

            for (var actions : new AbilityModifierAction[][] {
                modifierData.onAdded, modifierData.onRemoved, modifierData.onThinkInterval
            }) {
                if (actions == null) continue;
                for (var action : actions) {
                    if (action != null && action.modifierName != null) referenced.add(action.modifierName);
                }
            }

            if (modifierData.modifierMixins != null) {
                for (var mixin : modifierData.modifierMixins) {
                    if (mixin == null) continue;
                    referenced.addAll(mixin.modifierNameSteps);
                    if (mixin.modifierName != null && !mixin.modifierName.isJsonNull()) {
                        referenced.addAll(mixin.getModifierNames());
                    }
                }
            }
        }
        return referenced;
    }

    private void initializeEntityAbilities(EntityAvatar avatar) {
        var avatarData = avatar.getAvatar().getAvatarData();
        if (avatarData.getAbilities() != null) {
            for (int hash : avatarData.getAbilities()) addAbilityByHash(avatar, hash);
        }
        boolean inNatlan = player.getScene() != null && player.getScene().getId() == 101;
        int phlogistonHash = emu.grasscutter.utils.Utils.abilityHash("DynamicAbility_Phlogiston");
        for (int hash : emu.grasscutter.GameConstants.DEFAULT_ABILITY_HASHES) {
            if (hash == phlogistonHash && !inNatlan) continue;
            addAbilityByHash(avatar, hash);
        }
        for (int hash : player.getTeamManager().getTeamResonancesConfig()) {
            addAbilityByHash(avatar, hash);
        }
        var skillDepot = GameData.getAvatarSkillDepotDataMap().get(avatar.getAvatar().getSkillDepotId());
        if (skillDepot != null && skillDepot.getAbilities() != null) {
            for (int hash : skillDepot.getAbilities()) addAbilityByHash(avatar, hash);
        }
        for (String name : avatar.getAvatar().getExtraAbilityEmbryos()) {
            var data = GameData.getAbilityData(name);
            avatar.getInstancedAbilities().add(data != null ? new Ability(data, avatar, player) : null);
        }

        this.refreshGlobalValueWatchers(avatar);
    }

    private void invokeAction(
        AbilityModifierAction action, GameEntity target, GameEntity sourceEntity) {
    }

    private void handleModifierDurabilityChange(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
    }

    private static volatile Set<Integer> moonLightAbilityHashes;

    private static Set<Integer> getMoonLightAbilityHashes() {
        Set<Integer> s = moonLightAbilityHashes;
        if (s == null) {
            s = new HashSet<>();
            for (var e : GameData.getAbilityHashes().int2ObjectEntrySet()) {
                String name = e.getValue();
                if (name != null && name.startsWith("Avatar_") && name.endsWith("_MoonLight")) {
                    s.add(e.getIntKey());
                }
            }
            moonLightAbilityHashes = s;
        }
        return s;
    }

    private void handleAddNewAbility(AbilityInvokeEntry invoke)
        throws InvalidProtocolBufferException {
        var entity = this.player.getScene().getEntityById(invoke.getEntityId());

        if (entity == null) {
            Grasscutter.getLogger().trace("handleAddNewAbility: entity not found: {}", invoke.getEntityId());
            return;
        }

        var addAbility = AbilityMetaAddAbility.parseFrom(invoke.getAbilityData());
        var abString = addAbility.getAbility().getAbilityName();
        var abilityName = Ability.getAbilityName(abString);
        var abilityData = GameData.getAbilityData(abilityName);

        var abilities = entity.getInstancedAbilities();
        int targetIndex = invoke.getHead().getInstancedAbilityId() - 1;
        if (targetIndex < 0) return;

        while (abilities.size() < targetIndex) abilities.add(null);

        int abHash = abString.hasHash() ? abString.getHash() : 0;
        boolean isTeamMoonPhase = "TeamAbility_MoonPhase".equals(abilityName)
            || abHash == Utils.abilityHash("TeamAbility_MoonPhase");
        boolean isMoonLightAbility =
            (abilityName != null && abilityName.startsWith("Avatar_") && abilityName.endsWith("_MoonLight"))
            || getMoonLightAbilityHashes().contains(abHash);

        if (isTeamMoonPhase) {
            long moonCount = this.player.getTeamManager().getActiveTeam().stream()
                .filter(e -> PacketPlayerEnterSceneInfoNotify.getMoonphaseIds().contains(
                    e.getAvatar().getAvatarId()))
                .count();
            var teamEntity = this.player.getTeamManager().getEntity();
            int teamEntityId = teamEntity.getId();
            teamEntity.getGlobalAbilityValues().put("SGV_MoonPhaseLevel", (float) moonCount);
            this.player.sendPacket(new PacketServerGlobalValueChangeNotify(
                teamEntityId, "SGV_MoonPhaseLevel", (float) moonCount));
            if (moonCount > 0) {
                teamEntity.getGlobalAbilityValues().put("MoonOvergrowPoint_All", 50f);
                this.player.sendPacket(new PacketServerGlobalValueChangeNotify(
                    teamEntityId, "MoonOvergrowPoint_All", 50f));
            }
            log.debug("TeamAbility_MoonPhase loaded: sent SGV_MoonPhaseLevel={}, MoonOvergrowPoint_All={}",
                moonCount, moonCount > 0 ? 50 : 0);
        }

        if (isMoonLightAbility) {
            long moonCount = this.player.getTeamManager().getActiveTeam().stream()
                .filter(e -> PacketPlayerEnterSceneInfoNotify.getMoonphaseIds().contains(
                    e.getAvatar().getAvatarId()))
                .count();
            if (moonCount > 0) {
                var teamEntity = this.player.getTeamManager().getEntity();
                int teamEntityId = teamEntity.getId();
                teamEntity.getGlobalAbilityValues().put("MoonOvergrowPoint_All", 50f);
                this.player.sendPacket(new PacketServerGlobalValueChangeNotify(
                    teamEntityId, "MoonOvergrowPoint_All", 50f));
                log.debug("MoonLight ability {} loaded: re-sent MoonOvergrowPoint_All=50 to team entity={}",
                    abilityName, teamEntityId);
            }
        }

        if (abilityData == null) {
            Grasscutter.getLogger().trace(
                "handleAddNewAbility: ability data not found (stub at {}): entity={} name={} hash={}",
                targetIndex, entity.getId(), abilityName,
                abString.hasHash() ? abString.getHash() : 0);
            if (targetIndex == abilities.size()) abilities.add(null);
            else if (targetIndex < abilities.size() && abilities.get(targetIndex) == null) abilities.set(targetIndex, null);
            return;
        }

        var newAbility = new Ability(abilityData, entity, player);
        if (targetIndex < abilities.size() && abilities.get(targetIndex) == null) {
            abilities.set(targetIndex, newAbility);
        } else {
            abilities.add(newAbility);
        }

        if (abilityName != null && entity instanceof EntityClientGadget clientGadget) {
            var talentVars = GameData.getAbilityTalentVarMap().get(abilityName);
            if (talentVars != null && !talentVars.isEmpty()) {
                var ownerEntity = player.getScene().getEntityById(clientGadget.getOriginalOwnerEntityId());
                if (ownerEntity instanceof EntityAvatar avatarOwner) {
                    var avatar = avatarOwner.getAvatar();
                    AvatarSkillDepotData depot = GameData.getAvatarSkillDepotDataMap().get(avatar.getSkillDepotId());
                    List<AbilityInvokeEntry> overrides = new ArrayList<>();

                    for (var tv : talentVars) {
                        Integer groupId = GameData.getOpenConfigToProudSkillGroup().get(tv.openConfigName());
                        if (groupId == null) continue;

                        int skillLevel = 1;
                        if (depot != null) {
                            int resolvedGroupId = groupId;
                            skillLevel = depot.getSkillsAndEnergySkill()
                                .filter(skillId -> {
                                    var sc = GameData.getAvatarSkillDataMap().get(skillId);
                                    return sc != null && sc.getProudSkillGroupId() == resolvedGroupId;
                                })
                                .mapToObj(skillId -> avatar.getSkillLevelMap().getOrDefault(skillId, 1))
                                .findFirst()
                                .orElse(1);
                        }

                        ProudSkillData proudSkill = GameData.getProudSkillDataMap().get(groupId * 100 + skillLevel);
                        if (proudSkill == null) continue;
                        float[] paramList = proudSkill.getParamList();
                        if (paramList == null || tv.paramIndex() >= paramList.length) continue;

                        float value = paramList[tv.paramIndex()];
                        overrides.add(AbilityInvokeEntry.newBuilder()
                            .setEntityId(entity.getId())
                            .setArgumentType(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM)
                            .setHead(AbilityInvokeEntryHead.newBuilder()
                                .setInstancedAbilityId(targetIndex + 1)
                                .build())
                            .setAbilityData(AbilityScalarValueEntry.newBuilder()
                                .setKey(AbilityString.newBuilder()
                                    .setStr(tv.varName())
                                    .setHash(Utils.abilityHash(tv.varName()))
                                    .build())
                                .setFloatValue(value)
                                .build()
                                .toByteString())
                            .build());
                    }

                    if (!overrides.isEmpty()) {
                        player.sendPacket(new PacketAbilityInvocationsNotify(overrides));
                    }
                }
            }
        }

    }

    private void handleKillState(AbilityInvokeEntry invoke) throws InvalidProtocolBufferException {
        var scene = this.getPlayer().getScene();
        var entity = scene.getEntityById(invoke.getEntityId());
        if (entity == null) {
            Grasscutter.getLogger()
                .trace("Entity of ID {} was not found in the scene.", invoke.getEntityId());
            return;
        }

        var killState = AbilityMetaSetKilledState.parseFrom(invoke.getAbilityData());
        if (killState.getKilled()) {
            if (!(entity instanceof EntityAvatar) && !(entity instanceof EntityClientGadget)) {
                scene.killEntity(entity);
            }
        } else if (!entity.isAlive()) {
            if (entity instanceof EntityAvatar) {

                Grasscutter.getLogger()
                    .trace("Entity of ID {} is EntityAvatar. Ignoring", invoke.getEntityId());
                return;
            }
            if (entity.getFightProperties() == null) return;
            entity.setFightProperty(
                FightProperty.FIGHT_PROP_CUR_HP,
                entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
        }
    }

    public Map<String, Float> computeGadgetVarOverrides(EntityClientGadget gadget) {
        Map<String, Float> result = new HashMap<>();
        var ownerEntity = player.getScene().getEntityById(gadget.getOriginalOwnerEntityId());
        if (!(ownerEntity instanceof EntityAvatar avatarOwner)) return result;
        var avatar = avatarOwner.getAvatar();
        AvatarSkillDepotData depot = GameData.getAvatarSkillDepotDataMap().get(avatar.getSkillDepotId());

        List<String> abilityNames = new ArrayList<>();
        if (gadget.getConfigGadget() != null && gadget.getConfigGadget().getAbilities() != null) {
            for (var a : gadget.getConfigGadget().getAbilities()) {
                if (a.getAbilityName() != null) abilityNames.add(a.getAbilityName());
            }
        }
        var gd = GameData.getGadgetDataMap().get(gadget.getGadgetId());
        if (abilityNames.isEmpty() && gd != null && gd.getJsonName() != null) {
            abilityNames.add("Bullet_" + gd.getJsonName());
        }

        for (String abilityName : abilityNames) {
            var talentVars = GameData.getAbilityTalentVarMap().get(abilityName);
            if (talentVars == null) continue;
            for (var tv : talentVars) {
                Integer groupId = GameData.getOpenConfigToProudSkillGroup().get(tv.openConfigName());
                if (groupId == null) continue;
                int skillLevel = 1;
                if (depot != null) {
                    int resolvedGroupId = groupId;
                    skillLevel = depot.getSkillsAndEnergySkill()
                        .filter(sid -> {
                            var sc = GameData.getAvatarSkillDataMap().get(sid);
                            return sc != null && sc.getProudSkillGroupId() == resolvedGroupId;
                        })
                        .mapToObj(sid -> avatar.getSkillLevelMap().getOrDefault(sid, 1))
                        .findFirst()
                        .orElse(1);
                }
                ProudSkillData proudSkill = GameData.getProudSkillDataMap().get(groupId * 100 + skillLevel);
                if (proudSkill == null) continue;
                float[] paramList = proudSkill.getParamList();
                if (paramList == null || tv.paramIndex() >= paramList.length) continue;
                result.put(tv.varName(), paramList[tv.paramIndex()]);
            }
        }
        return result;
    }

    public void addAbilityToEntity(GameEntity entity, String name) {
        AbilityData data = GameData.getAbilityData(name);
        if (data != null) addAbilityToEntity(entity, data);
    }

    public void addAbilityToEntity(GameEntity entity, AbilityData abilityData) {
        var ability = new Ability(abilityData, entity, this.player);
        entity.getInstancedAbilities().add(ability);
        fireAbilityOnAdded(ability, entity);
    }

    private void fireAbilityOnAdded(Ability ability, GameEntity entity) {
        var data = ability.getData();
        if (data == null) return;

        if (data.onAdded != null) {
            for (var action : data.onAdded) {
                if (action.type == null) continue;
                executeAction(ability, action, com.google.protobuf.ByteString.EMPTY, entity);
            }
        }

        if (data.modifiers != null) {
            var defaultMod = data.modifiers.get("Default");
            if (defaultMod != null && defaultMod.onAdded != null) {
                for (var action : defaultMod.onAdded) {
                    if (action.type == null) continue;
                    executeAction(ability, action, com.google.protobuf.ByteString.EMPTY, entity);
                }
            }
        }
    }
}
