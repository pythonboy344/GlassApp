package com.glassapp.app

data class Preset(
    val name: String,
    val base: Double,
    val beat: Double,
    val iso: Boolean,
    val description: String
)

val ADVANCED_PRESETS = listOf(
    Preset("Superhuman (Limitless)", 165.0, 40.0, true, "Multi-stacking Gamma & Alpha. Designed for peak cognitive performance and 'Genius' state flow."),
    Preset("Awakened Mind (Flow)", 144.0, 14.0, false, "Anna Wise's signature. Bridges intuition with logic. Ideal for coding and logistics."),
    Preset("SMR (ADHD Calm)", 150.0, 12.5, true, "Sensorimotor Rhythm. Reduces restlessness and improves physical/mental stability."),
    Preset("Gamma Binding", 160.0, 40.0, true, "High-level information processing. Best for complex debugging or high-speed gaming."),
    Preset("Schumann (Earth)", 132.0, 7.83, false, "Earth's heartbeat. Use after your shift to lower cortisol and reset the nervous system."),
    Preset("Deep Theta (Zen)", 120.0, 5.5, false, "Insight state. Use for subconscious problem solving and high-level creative work."),
    Preset("CUSTOM", 150.0, 10.0, false, "Manual laboratory mode. Precise frequency tuning for experimental focus.")
)