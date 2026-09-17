package com.docssuite.core

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

data class AppFont(val label: String, val family: FontFamily)

/**
 * Familles de polices fournies par Android. `Typeface.create` ne échoue jamais :
 * un nom inconnu retombe simplement sur la police par défaut de l'appareil.
 */
private fun systemFamily(name: String): FontFamily =
    FontFamily(Typeface.create(name, Typeface.NORMAL))

val AppFonts: List<AppFont> by lazy {
    listOf(
        AppFont("Défaut", FontFamily.Default),
        AppFont("Sans Serif", FontFamily.SansSerif),
        AppFont("Serif", FontFamily.Serif),
        AppFont("Monospace", FontFamily.Monospace),
        AppFont("Manuscrite", FontFamily.Cursive),
        AppFont("Roboto", systemFamily("sans-serif")),
        AppFont("Roboto Light", systemFamily("sans-serif-light")),
        AppFont("Roboto Thin", systemFamily("sans-serif-thin")),
        AppFont("Roboto Medium", systemFamily("sans-serif-medium")),
        AppFont("Roboto Black", systemFamily("sans-serif-black")),
        AppFont("Roboto Condensed", systemFamily("sans-serif-condensed")),
        AppFont("Condensed Light", systemFamily("sans-serif-condensed-light")),
        AppFont("Condensed Medium", systemFamily("sans-serif-condensed-medium")),
        AppFont("Petites capitales", systemFamily("sans-serif-smallcaps")),
        AppFont("Noto Serif", systemFamily("notoserif")),
        AppFont("Serif Monospace", systemFamily("serif-monospace")),
        AppFont("Décontractée", systemFamily("casual")),
        AppFont("Cursive système", systemFamily("cursive")),
        AppFont("Droid Sans", systemFamily("droid-sans")),
        AppFont("Droid Serif", systemFamily("droid-serif"))
    )
}

fun fontFamilyAt(index: Int): FontFamily = AppFonts.getOrElse(index) { AppFonts[0] }.family

fun fontLabelAt(index: Int): String = AppFonts.getOrElse(index) { AppFonts[0] }.label

val FontSizes = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 96)
