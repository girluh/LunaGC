package emu.grasscutter.data.binout;

import com.google.gson.*;

import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.common.DynamicFloat;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

public class AbilityMixinData implements Serializable {
    private static final long serialVersionUID = -2001232313615923575L;

    public enum Type {
        AttachToGadgetStateMixin,
        AttachToStateIDMixin,
        SetGadgetStateV2,
        ShieldBarMixin,
        AvatarCombatMixin,
        DoActionByEventMixin,
        DoActionByKillingMixin,
        SkillButtonHoldChargeMixin,
        GlobalSubShieldMixin,
        TileAttackMixin,
        SwitchHealToHPDebtsMixin,
        AttachModifierToGlobalValueMixin,
        DJLJLPAFPGN,
        KENHGCLICPB,
        DoActionOnGlobalValueChangeMixin,
        CurLocalAvatarMixin,
        NyxCostMixin,
        ModifyDamageMixin,
        AvatarChangeSkillMixin,
        KHOENFHDFJE,
        HJKDMEOOBDK,
        FIGCOCJJHCH,
        DMKDPHHJENO,
        LAAJCBLNLDO,
        CameraBlurMixin,
        AttachToNormalizedTimeMixin,
        AttachToMultiNormalizedTimeMixin,
        DLJBCMKDMEK,
        PhlogistonCostMixin,
        FIHACJPNNED,
        JMEOJHGPNMB,
        AttachModifierToSelfGlobalValueMixin,
        AttachActionToModifierMixin,
        AttachModifierToSelfGlobalValueNoInitMixin,
        HPDebtsMixin,
        LimitHpDebtsByTagMixin,
        TileAttackManagerMixin,
        CostStaminaMixin,
        DoActionByEnergyChangeMixin,
        RejectAttackMixin,
        DoActionByTargetsCountMixin,
        AttachToAbilityStateMixin,
        IPIBBIDFDOL,
        EBBCNBHOAIP,
        ReviveElemEnergyMixin,
        HKGPGGJAKGL,
        ENPGGGNLLJG,
        PBOJOFIGPIC,
        IEABBMGDJHC,
        ModifyBeHitDamageMixin,
        DoActionByCreateGadgetMixin,
        MuteHitEffectMixin,
        EntityInVisibleMixin,
        DDCOPGJBHLB,
        IBAMBHPLNNA,
        PCKKGOMJIKL,
        TriggerPostProcessEffectMixin,
        JGOOOFOCJBI,
        OOAMMMJMKPD,
        EFDAMNIDHDC,
        JMJFEPHFFFN,
        AttachToAnimatorStateIDMixin,
        AvatarSteerByCameraMixin,
        ModifyDamageCountMixin,
        AttackCostElementMixin,
        OnAvatarUseSkillMixin,
        DoActionByElementReactionMixin,
        DoActionBySelfElementReactionMixin,
        CurLocalAvatarMixinV2
    }
    public AbilityModifierAction[] idontknowwhattonamethis;
    public AbilityModifierAction[] idontknowwhattonamethis2;

    @SerializedName("onEnterCombat")
    public AbilityModifierAction[] onEnterCombat;

    @SerializedName("onExitCombat")
    public AbilityModifierAction[] onExitCombat;

    @SerializedName("onTriggerSkill")
    public AbilityModifierAction[] onTriggerSkill;

    @SerializedName("onTriggerUltimateSkill")
    public AbilityModifierAction[] onTriggerUltimateSkill;

    public AbilityModifierAction[] IOKPLLOKGGJ;
    
    @SerializedName("onKill")
    public AbilityModifierAction[] onKill;
    
    @SerializedName("successActions")
    public AbilityModifierAction[] successActions;

    @SerializedName("succActions")
    public AbilityModifierAction[] succActions;

    @SerializedName("actions")
    public AbilityModifierAction[] actions;

    @SerializedName("actionQueue")
    public AbilityModifierAction[] actionQueue;

    public List<String> reactionTypes = new ArrayList<>();
    public List<String> entityTypes = new ArrayList<>();
    public List<String> attackTags = new ArrayList<>();

    @SerializedName(value = "tags", alternate = "FCGCFFJHAHM")
    public List<String> debtTags = new ArrayList<>();

    @SerializedName(value = "maxRatio", alternate = "HPADGKHLLHD")
    public DynamicFloat debtMaxRatio = DynamicFloat.ZERO;

    @SerializedName(value = "predicates", alternate = "GLCOHEDMLDM")
    public List<Object> predicates;

    @SerializedName("$type")
    public Type type;

    public JsonElement modifierName;


    public DynamicFloat speed = DynamicFloat.ZERO;
    public DynamicFloat costStaminaDelta = DynamicFloat.ZERO;
    public DynamicFloat ratio = DynamicFloat.ONE;
    public DynamicFloat detectWindow = DynamicFloat.ONE;
    public String globalValueKey;
    public List<String> stateIDs = new ArrayList<>();
    public String stateID;
    public DynamicFloat defaultGlobalValueOnCreate = DynamicFloat.ZERO;
    public List<DynamicFloat> ratioSteps = new ArrayList<>();
    public List<DynamicFloat> valueSteps = new ArrayList<>();
    public String globalValueTarget;
    public boolean removeAppliedModifier = true;
    public List<String> modifierNameSteps = new ArrayList<>();
    public boolean EJEMBMFPBKF = true;
    public boolean isCheckOnAttach = true;
    public boolean AMFABNCKJNG = true;
    public boolean forceStopWhenRemoved = true;
    public boolean FKAJIEOFOAB = true;
    public List<String> getModifierNames() {
        if (modifierName.isJsonArray()) {
            java.lang.reflect.Type listType = (new TypeToken<List<String>>() {}).getType();
            List<String> list = (new Gson()).fromJson(modifierName, listType);
            return list;
        } else {
            return Arrays.asList(modifierName.getAsString());
        }
    }
}