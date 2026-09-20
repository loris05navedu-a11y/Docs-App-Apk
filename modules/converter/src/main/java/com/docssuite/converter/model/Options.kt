package com.docssuite.converter.model

/** Échelle appliquée à la sortie. `ORIGINAL` ne retouche jamais les dimensions. */
enum class ScalePreset(val label: String, val shortLabel: String, val factor: Float) {
    ORIGINAL("Taille d'origine", "100 %", 1f),
    LARGE("Grand — 75 %", "75 %", 0.75f),
    MEDIUM("Moyen — 50 %", "50 %", 0.5f),
    SMALL("Petit — 25 %", "25 %", 0.25f)
}

enum class Rotation(val label: String, val shortLabel: String, val degrees: Int) {
    NONE("Aucune", "0°", 0),
    RIGHT("90° à droite", "90°", 90),
    HALF("180°", "180°", 180),
    LEFT("90° à gauche", "270°", 270)
}

enum class PageSize(val label: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842),
    LETTER("Lettre US", 612, 792),
    FIT_IMAGE("Ajustée à l'image", 0, 0)
}

enum class PageOrientation(val label: String) { PORTRAIT("Portrait"), LANDSCAPE("Paysage") }

enum class AudioBitrate(val label: String, val bitsPerSecond: Int) {
    LOW("96 kbit/s — léger", 96_000),
    STANDARD("128 kbit/s — standard", 128_000),
    HIGH("192 kbit/s — haute qualité", 192_000),
    MAX("256 kbit/s — maximale", 256_000)
}

enum class SampleRate(val label: String, val hertz: Int) {
    ORIGINAL("Identique à la source", 0),
    CD("44 100 Hz", 44_100),
    BROADCAST("48 000 Hz", 48_000)
}

/**
 * Réglages d'une conversion. Un seul objet pour toutes les familles : chaque
 * écran n'affiche que le sous-ensemble pertinent, et le moteur ne lit que ce
 * qui concerne le format visé.
 */
data class ConversionOptions(
    // Image
    val quality: Int = 90,
    val scale: ScalePreset = ScalePreset.ORIGINAL,
    val rotation: Rotation = Rotation.NONE,
    val keepMetadata: Boolean = true,
    // PDF
    val pageSize: PageSize = PageSize.FIT_IMAGE,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val mergeIntoSinglePdf: Boolean = true,
    // Audio
    val audioBitrate: AudioBitrate = AudioBitrate.STANDARD,
    val sampleRate: SampleRate = SampleRate.ORIGINAL,
    // Vidéo
    val keepVideoTrack: Boolean = true
)
