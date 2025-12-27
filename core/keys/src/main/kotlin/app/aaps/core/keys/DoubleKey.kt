package app.aaps.core.keys

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),

    //  Eigen



    // FCL vNext
    Dag_resistentie_target("Dag_resistentie_target", 5.2,4.0,8.0),
    Nacht_resistentie_target("Dacht_resistentie_target", 5.2,4.0,8.0),
    Uren_resistentie("Uren_resistentie", 2.5,1.0,5.0),
    stap_TT("stap_TT", 2.0,0.5,4.0),
    max_bolus_day("max_bolus_day", 1.25,0.1,8.0),
    max_bolus_night("max_bolus_night", 0.5,0.1,8.0),

    fcl_vnext_k_delta("fcl_vnext_k_delta", 1.0,0.3,1.5),
    fcl_vnext_k_slope("fcl_vnext_k_slope", 0.45,0.0,1.5),
    fcl_vnext_k_accel("fcl_vnext_k_accel", 0.55,0.0,1.0),
    fcl_vnext_min_consistency("fcl_vnext_min_consistency", 0.18,0.0,0.6),
    fcl_vnext_consistency_exp("fcl_vnext_consistency_exp", 1.0,0.5,2.0),
    fcl_vnext_iob_start("fcl_vnext_iob_start", 0.4,0.2,0.7),
    fcl_vnext_iob_max("fcl_vnext_iob_max", 0.75,0.5,1.2),
    fcl_vnext_iob_min_factor("fcl_vnext_iob_min_factor", 0.1,0.02,0.4),
    fcl_vnext_gain_day("fcl_vnext_gain_day", 1.0,0.3,2.0),
    fcl_vnext_gain_night("fcl_vnext_gain_night", 0.7,0.2,1.5),

    fcl_vnext_stagnation_delta_min("fcl_vnext_stagnation_delta_min", 0.8,0.4,3.0),
    fcl_vnext_stagnation_slope_max_neg("fcl_vnext_stagnation_slope_max_neg", -0.25,-1.0,0.0),
    fcl_vnext_stagnation_slope_max_pos("fcl_vnext_stagnation_slope_max_pos", 0.25,0.0,1.0),
    fcl_vnext_stagnation_energy_boost("fcl_vnext_stagnation_energy_boost", 0.12,0.0,0.5),
    fcl_vnext_stagnation_accel_max_abs("fcl_vnext_stagnation_accel_max_abs", 0.06,0.01,0.3),

    fcl_vnext_absorption_dose_factor("fcl_vnext_absorption_dose_factor", 0.0,0.0,0.5),
    fcl_vnext_bg_smoothing_alpha("fcl_vnext_bg_smoothing_alpha", 0.4,0.1,0.8),

    fcl_vnext_commit_iob_power("fcl_vnext_commit_iob_power", 1.0,0.5,2.0),


}