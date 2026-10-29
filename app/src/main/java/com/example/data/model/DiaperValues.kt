package com.example.data.model

import com.example.R

/**
 * The shown name of a stored diaper value (amount, consistency, color or cream, long or short form). The stored
 * values stay English, since they round-trip through the server notes ("Amount: Light | Consistency: …").
 */
fun diaperValueLabel(value: String): String = DIAPER_VALUE_LABELS[value]?.let { AppText.get(it) } ?: value

private val DIAPER_VALUE_LABELS = mapOf(
    "Light" to R.string.diaper_amount_light,
    "Medium" to R.string.diaper_amount_medium,
    "Heavy" to R.string.diaper_amount_heavy,
    "Loose / Liquid" to R.string.diaper_consistency_loose_long,
    "Seedy / Mustard" to R.string.diaper_consistency_seedy_long,
    "Paste / Normal" to R.string.diaper_consistency_paste_long,
    "Hard / Dry" to R.string.diaper_consistency_hard_long,
    "Loose" to R.string.diaper_consistency_loose,
    "Seedy" to R.string.diaper_consistency_seedy,
    "Paste" to R.string.diaper_consistency_paste,
    "Hard" to R.string.diaper_consistency_hard,
    "Soft" to R.string.diaper_consistency_soft,
    "Mustard" to R.string.diaper_color_mustard,
    "Tan/Brown" to R.string.diaper_color_brown,
    "Greenish" to R.string.diaper_color_greenish,
    "Dark" to R.string.diaper_color_dark,
    "Aquaphor / Barrier" to R.string.diaper_cream_barrier_long,
    "Zinc Oxide Cream" to R.string.diaper_cream_zinc_long,
    "Baby Powder" to R.string.diaper_cream_powder_long,
    "Barrier" to R.string.diaper_cream_barrier,
    "Zinc" to R.string.diaper_cream_zinc,
    "Powder" to R.string.diaper_cream_powder
)

/** "Wet", "Dirty", "Both", "Dry". */
fun diaperTypeLabel(type: DiaperType): String = AppText.get(
    when (type) {
        DiaperType.WET -> R.string.diaper_wet
        DiaperType.DIRTY -> R.string.diaper_dirty
        DiaperType.BOTH -> R.string.diaper_both
        DiaperType.DRY -> R.string.diaper_dry
    }
)
