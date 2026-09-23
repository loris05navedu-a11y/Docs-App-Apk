package com.docssuite.presentation

import com.docssuite.core.AppFonts
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.fontLabelAt
import com.docssuite.fileformats.Deck
import com.docssuite.fileformats.SlideLayout
import com.docssuite.fileformats.SlideModel

/** Traduction entre les diapositives de l'éditeur et le modèle pivot des fichiers. */

/** Réglages communs à toute la présentation. */
internal data class DeckSettings(val footer: String = "", val slideNumbers: Boolean = false)

private fun fontIndexOf(label: String): Int =
    AppFonts.indexOfFirst { it.label.equals(label, ignoreCase = true) }.takeIf { it >= 0 } ?: 0

internal fun buildDeck(name: String, slides: List<Slide>, settings: DeckSettings = DeckSettings()): Deck = Deck(
    title = name,
    slides = slides.map { slide ->
        SlideModel(
            title = slide.title,
            content = slide.content,
            background = slide.background,
            textColor = slide.textColor,
            titleSize = slide.titleSize,
            contentSize = slide.contentSize,
            align = slide.align,
            fontName = fontLabelAt(slide.fontIndex),
            notes = slide.notes,
            layout = slide.layout,
            secondContent = slide.secondContent,
            bullets = slide.bullets
        )
    },
    footer = settings.footer,
    slideNumbers = settings.slideNumbers
)

internal fun settingsOf(deck: Deck) = DeckSettings(deck.footer, deck.slideNumbers)

internal fun decodeDeckModel(deck: Deck): List<Slide> = deck.slides.map { slide ->
    Slide(
        title = slide.title,
        content = slide.content,
        background = slide.background,
        textColor = slide.textColor,
        titleSize = slide.titleSize.coerceIn(10, 96),
        contentSize = slide.contentSize.coerceIn(8, 72),
        align = slide.align.coerceIn(0, 2),
        fontIndex = fontIndexOf(slide.fontName),
        // Les fichiers PowerPoint portent des transitions bien plus variées que
        // celles de l'app : on repart du fondu plutôt que d'en inventer une.
        transition = 1,
        notes = slide.notes,
        layout = slide.layout.coerceIn(SlideLayout.TITLE_AND_CONTENT, SlideLayout.TWO_COLUMNS),
        secondContent = slide.secondContent,
        bullets = slide.bullets
    )
}

/** Enregistre une présentation importée et renvoie son identifiant. */
fun saveImportedDeck(storage: DocumentStorage, deck: Deck, name: String): String {
    val slides = decodeDeckModel(deck).ifEmpty { listOf(Slide(title = name)) }
    val id = storage.newId()
    storage.save(id, name, DocType.DECK, encodeDeck(slides, settingsOf(deck)))
    return id
}
