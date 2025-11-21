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

    persistent_Dagdrempel("persistent_Dagdrempel", 8.5,7.0,12.0),
    persistent_Nachtdrempel("persistent_Nachtdrempel",7.0,6.0,10.0),
    persistent_Dag_MaxBolus("persistent_Dag_MaxBolus", 0.5,0.1,1.0),
    persistent_Nacht_MaxBolus("persistent_Nacht_MaxBolus",0.3,0.1,1.0),


    stap_TT("stap_TT", 2.0,0.5,4.0),
    max_bolus("max_bolus", 1.25,0.5,8.0),
    meal_detection_sensitivity("meal_detection_sensitivity", 0.35,0.1,0.5),
    CarbISF_min_Factor("CarbISF_min_Factor", 0.9,0.5,1.0),
    CarbISF_max_Factor("CarbISF_max_Factor", 1.1,1.0,2.5),

    hypoThresholdDay("hypoThresholdDay", 4.0,3.8,4.5),
    hypoThresholdNight("hypoThresholdNight", 4.5,3.8,5.0),
    hypoRecoveryBGRange("hypoRecoveryBGRange", 2.5,1.5,3.0),


    Dag_resistentie_target("Dag_resistentie_target", 5.2,4.0,8.0),
    Nacht_resistentie_target("Dacht_resistentie_target", 5.2,4.0,8.0),
    Uren_resistentie("Uren_resistentie", 2.5,1.0,5.0),

    phase_rising_slope("phase_rising_slope", 1.5,0.3,3.0),
    phase_plateau_slope("phase_plateau_slope", 0.3,0.0,1.0),

//    phase_early_rise_slope("phase_early_rise_slope", 1.0,0.3,2.5),
//    phase_mid_rise_slope("phase_mid_rise_slope", 1.0,0.3,2.5),
//    phase_late_rise_slope("phase_late_rise_slope", 0.4,0.1,1.0),
    phase_peak_slope("phase_peak_slope", 0.1,-0.5,0.8),
    phase_early_rise_accel("phase_early_rise_accel", 0.2,0.05,0.5),
    phase_min_consistency("phase_min_consistency", 0.6,0.3,0.9),

    dynamic_night_aggressiveness_threshold("dynamic_night_aggressiveness_threshold", 2.0,0.5,5.0),

    data_smoothing_alpha("data_smoothing_alpha", 0.4,0.1,0.8),
    direction_consistency_threshold("direction_consistency_threshold", 0.7,0.3,0.9),

}