package com.docssuite.templates

import com.docssuite.fileformats.CellStyle
import com.docssuite.fileformats.DocBlock
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TableCell
import com.docssuite.fileformats.TableRow
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.TextTable
import com.docssuite.fileformats.Workbook

/**
 * Les modèles prêts à remplir. Les documents s'ouvrent dans l'éditeur de
 * texte, les factures, devis et budgets dans le tableur, où les totaux se
 * recalculent à chaque modification.
 */
object Templates {

    private const val ACCENT = 0xFF1E3A8AL
    private const val GREY = 0xFF64748BL
    private const val HEADER_FILL = 0xFFDBEAFEL
    private const val TOTAL_FILL = 0xFFF1F5F9L

    private val profile = listOf(
        Field("nom", "Prénom et nom", profile = true),
        Field("adresse", "Adresse", "N° et rue", profile = true),
        Field("ville", "Code postal et ville", profile = true),
        Field("telephone", "Téléphone", profile = true),
        Field("email", "E-mail", profile = true)
    )

    val all: List<Template> by lazy {
        listOf(cv(), coverLetter(), cancellation(), attestation(), minutes(), invoice(quote = false), invoice(quote = true), budget())
    }

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    // ------------------------------------------------------------ texte

    private fun run(text: String, bold: Boolean = false, size: Int = 16, color: Long = 0xFF1A1A1AL, italic: Boolean = false) =
        TextRun(text, bold = bold, italic = italic, size = size, color = color)

    private fun para(text: String = "", bold: Boolean = false, size: Int = 16, align: Int = 0, color: Long = 0xFF1A1A1AL, italic: Boolean = false) =
        TextParagraph(if (text.isEmpty()) emptyList() else listOf(run(text, bold, size, color, italic)), align = align)

    private fun heading(text: String, level: Int = 1) =
        TextParagraph(listOf(run(text, bold = true, size = if (level == 1) 20 else 17, color = ACCENT)), heading = level)

    private fun blank() = TextParagraph()

    private fun bullet(text: String) = TextParagraph(listOf(run("•  $text")), indent = 1)

    private fun table(header: List<String>, rows: List<List<String>>) = TextTable(
        listOf(TableRow(header.map { TableCell(listOf(para(it, bold = true)), fill = HEADER_FILL) })) +
            rows.map { row -> TableRow(row.map { TableCell(listOf(para(it))) }) },
        headerRow = true
    )

    private fun contact(v: Values): List<TextParagraph> =
        listOf(para(v["nom"], bold = true), para(v["adresse"]), para(v["ville"]), para(v["telephone"]), para(v["email"]))

    private fun text(title: String, blocks: List<DocBlock>) = Built.Text(TextDocument(title, blocks), title)

    private fun cv() = Template(
        "cv", "CV", "Un CV clair en une page : profil, expériences, formation, compétences",
        TemplateGroup.WORK, sheet = false,
        profile + listOf(
            Field("metier", "Poste recherché", "Ex. : Assistante de gestion"),
            Field("profil", "Profil en quelques phrases", "Qui êtes-vous, ce que vous cherchez", multiline = true),
            Field("experiences", "Expériences", "Une par ligne : 2022-2024 – Vendeur – Boulangerie Martin", multiline = true),
            Field("formations", "Formations", "Une par ligne : 2021 – BTS MCO – Lycée Victor Hugo", multiline = true),
            Field("competences", "Compétences", "Une par ligne", multiline = true),
            Field("langues", "Langues", "Ex. : Anglais courant, espagnol notions"),
            Field("loisirs", "Centres d'intérêt", "Ex. : Football en club, photographie")
        )
    ) { v ->
        fun dated(line: String): TextParagraph {
            val parts = line.split(" – ", " - ", limit = 2)
            return if (parts.size == 2) {
                TextParagraph(listOf(run(parts[0].trim() + "   ", bold = true, color = ACCENT), run(parts[1].trim())))
            } else {
                para(line)
            }
        }
        val blocks = ArrayList<DocBlock>()
        blocks += para(v["nom"], bold = true, size = 28, align = 1)
        blocks += para(v["metier"], size = 19, align = 1, color = ACCENT)
        blocks += para(listOf(v["adresse"], v["ville"]).joinToString(", "), size = 14, align = 1, color = GREY)
        blocks += para("${v["telephone"]}  ·  ${v["email"]}", size = 14, align = 1, color = GREY)
        blocks += blank()
        blocks += heading("Profil")
        blocks += v.lines("profil").map { para(it) }
        blocks += heading("Expérience professionnelle")
        blocks += v.lines("experiences").map(::dated)
        blocks += heading("Formation")
        blocks += v.lines("formations").map(::dated)
        blocks += heading("Compétences")
        blocks += v.lines("competences").map(::bullet)
        blocks += heading("Langues")
        blocks += para(v["langues"])
        blocks += heading("Centres d'intérêt")
        blocks += para(v["loisirs"])
        text("CV ${v["nom"]}".trim(), blocks)
    }

    private fun coverLetter() = Template(
        "motivation", "Lettre de motivation", "Structure classique vous / moi / nous, prête à personnaliser",
        TemplateGroup.WORK, sheet = false,
        profile + listOf(
            Field("entreprise", "Entreprise"),
            Field("adresse_entreprise", "Adresse de l'entreprise", multiline = true),
            Field("poste", "Poste visé"),
            Field("annonce", "Référence de l'annonce", "Facultatif"),
            Field("lieu", "Fait à", "Votre ville")
        )
    ) { v ->
        val blocks = ArrayList<DocBlock>()
        blocks += contact(v)
        blocks += blank()
        blocks += para(v["entreprise"], bold = true, align = 2)
        blocks += v.lines("adresse_entreprise").map { para(it, align = 2) }
        blocks += blank()
        blocks += para("${v["lieu"]}, le ${v.date()}", align = 2)
        blocks += blank()
        val subject = "Objet : candidature au poste de ${v["poste"]}" + if (v.has("annonce")) " (réf. ${v["annonce"]})" else ""
        blocks += para(subject, bold = true)
        blocks += blank()
        blocks += para("Madame, Monsieur,")
        blocks += blank()
        blocks += para(
            "Votre entreprise ${v["entreprise"]} [ce qui vous attire chez elle : ses produits, ses valeurs, un projet récent]. " +
                "C'est pourquoi je vous propose ma candidature au poste de ${v["poste"]}."
        )
        blocks += blank()
        blocks += para(
            "[Votre parcours en 3 ou 4 phrases : l'expérience ou la formation la plus utile pour ce poste, " +
                "un résultat concret, les compétences que vous apporterez.]"
        )
        blocks += blank()
        blocks += para(
            "Rejoindre votre équipe me permettrait de [ce que vous apporterez ensemble]. " +
                "Je serais heureux·se de vous exposer plus en détail mes motivations lors d'un entretien."
        )
        blocks += blank()
        blocks += para(
            "Je vous prie d'agréer, Madame, Monsieur, l'expression de mes salutations distinguées."
        )
        blocks += blank()
        blocks += para(v["nom"], align = 2)
        text("Lettre de motivation – ${v["entreprise"]}", blocks)
    }

    private fun cancellation() = Template(
        "resiliation", "Lettre de résiliation", "Abonnement, assurance, salle de sport, box internet…",
        TemplateGroup.LETTERS, sheet = false,
        profile + listOf(
            Field("organisme", "Organisme", "Opérateur, assurance, club…"),
            Field("adresse_organisme", "Adresse de l'organisme", "Service résiliation", multiline = true),
            Field("contrat", "N° de contrat ou de client"),
            Field("effet", "Date de résiliation souhaitée", default = "la prochaine échéance possible"),
            Field("lieu", "Fait à", "Votre ville")
        )
    ) { v ->
        val blocks = ArrayList<DocBlock>()
        blocks += contact(v)
        blocks += blank()
        blocks += para(v["organisme"], bold = true, align = 2)
        blocks += v.lines("adresse_organisme").map { para(it, align = 2) }
        blocks += blank()
        blocks += para("${v["lieu"]}, le ${v.date()}", align = 2)
        blocks += blank()
        blocks += para("Lettre recommandée avec accusé de réception", italic = true, color = GREY)
        blocks += para("Objet : résiliation du contrat n° ${v["contrat"]}", bold = true)
        blocks += blank()
        blocks += para("Madame, Monsieur,")
        blocks += blank()
        blocks += para(
            "Par la présente, je vous informe de ma décision de résilier le contrat n° ${v["contrat"]} " +
                "souscrit auprès de ${v["organisme"]} au nom de ${v["nom"]}, avec effet à ${v["effet"]}."
        )
        blocks += blank()
        blocks += para(
            "Je vous remercie de bien vouloir m'adresser une confirmation écrite de cette résiliation, " +
                "d'arrêter tout prélèvement à compter de cette date et, le cas échéant, de me rembourser " +
                "les sommes perçues pour la période non couverte."
        )
        blocks += blank()
        blocks += para("Je vous prie d'agréer, Madame, Monsieur, l'expression de mes salutations distinguées.")
        blocks += blank()
        blocks += para(v["nom"], align = 2)
        text("Résiliation – ${v["organisme"]}", blocks)
    }

    private fun attestation() = Template(
        "attestation", "Attestation sur l'honneur", "Hébergement, perte de document, déclaration… à signer",
        TemplateGroup.LETTERS, sheet = false,
        profile + listOf(
            Field("naissance", "Date et lieu de naissance", "Ex. : 12/03/1995 à Lyon"),
            Field("objet", "Ce que vous attestez", "Ex. : héberger à mon domicile M. Paul Durand depuis le 1er janvier 2026", multiline = true),
            Field("lieu", "Fait à", "Votre ville")
        )
    ) { v ->
        val blocks = ArrayList<DocBlock>()
        blocks += para("ATTESTATION SUR L'HONNEUR", bold = true, size = 22, align = 1)
        blocks += blank()
        blocks += blank()
        blocks += para("Je soussigné·e ${v["nom"]},")
        blocks += para("né·e le ${v["naissance"]},")
        blocks += para("demeurant ${v["adresse"]}, ${v["ville"]},")
        blocks += blank()
        blocks += para("atteste sur l'honneur :")
        blocks += blank()
        blocks += v.lines("objet").map { para(it) }
        blocks += blank()
        blocks += para("Fait pour servir et valoir ce que de droit.")
        blocks += blank()
        blocks += para(
            "Je suis informé·e qu'une fausse attestation m'expose aux sanctions prévues par l'article 441-7 du Code pénal.",
            size = 13, color = GREY, italic = true
        )
        blocks += blank()
        blocks += para("Fait à ${v["lieu"]}, le ${v.date()}", align = 2)
        blocks += blank()
        blocks += para("Signature :", align = 2)
        text("Attestation sur l'honneur", blocks)
    }

    private fun minutes() = Template(
        "compte-rendu", "Compte rendu de réunion", "Participants, ordre du jour, décisions et tableau des actions",
        TemplateGroup.MEETINGS, sheet = false,
        listOf(
            Field("objet", "Objet de la réunion"),
            Field("lieu_reunion", "Lieu", "Salle, visio…"),
            Field("participants", "Participants", "Un par ligne", multiline = true),
            Field("absents", "Absents excusés", "Facultatif"),
            Field("ordre", "Ordre du jour", "Un point par ligne", multiline = true),
            Field("redacteur", "Rédigé par")
        )
    ) { v ->
        val blocks = ArrayList<DocBlock>()
        blocks += para("Compte rendu de réunion", bold = true, size = 24)
        blocks += para(v["objet"], size = 19, color = ACCENT)
        blocks += blank()
        blocks += table(
            listOf("Date", "Lieu", "Rédigé par"),
            listOf(listOf(v.date(), v["lieu_reunion"], v["redacteur"]))
        )
        blocks += heading("Participants")
        blocks += v.lines("participants").map(::bullet)
        if (v.has("absents")) blocks += para("Absents excusés : ${v["absents"]}", italic = true, color = GREY)
        blocks += heading("Ordre du jour")
        val points = v.lines("ordre")
        points.forEachIndexed { i, point -> blocks += para("${i + 1}. $point") }
        points.forEachIndexed { i, point ->
            blocks += heading("${i + 1}. $point", level = 2)
            blocks += para("[Échanges, points de vue, chiffres présentés]", color = GREY)
            blocks += para("Décision : [ce qui a été décidé]", bold = true)
        }
        blocks += heading("Actions à mener")
        blocks += table(listOf("Action", "Responsable", "Échéance"), List(4) { listOf("", "", "") })
        blocks += blank()
        blocks += para("Prochaine réunion : [date, heure, lieu]", bold = true)
        text("CR ${v["objet"]} ${v.shortDate()}", blocks)
    }

    // ------------------------------------------------------------ tableur

    /** Une feuille qu'on remplit cellule par cellule (références A1). */
    private class SheetBuilder(val name: String) {
        val cells = LinkedHashMap<String, String>()
        val styles = LinkedHashMap<String, CellStyle>()

        fun put(ref: String, value: String, style: CellStyle? = null) {
            if (value.isNotEmpty()) cells[ref] = value
            if (style != null) styles[ref] = style
        }

        fun build(columns: Int, rows: Int) = Sheet(name, cells, styles, columns, rows)
    }

    private val bold = CellStyle(bold = true)
    private val headerStyle = CellStyle(bold = true, background = HEADER_FILL)
    private val totalStyle = CellStyle(bold = true, background = TOTAL_FILL, align = 3)
    private val rightStyle = CellStyle(align = 3)
    private val titleStyle = CellStyle(bold = true, color = ACCENT)
    private val noteStyle = CellStyle(italic = true, color = GREY)

    /** Montant affiché à la française : « 1 234,50 € ». */
    private fun euros(expression: String) = "=TEXTE($expression;\"# ##0.00\")&\" €\""

    const val FIRST_LINE = 14
    const val LAST_LINE = 28

    private fun invoice(quote: Boolean) = Template(
        if (quote) "devis" else "facture",
        if (quote) "Devis" else "Facture",
        if (quote) "Lignes, TVA et total calculés, « bon pour accord » à signer"
        else "Quantité × prix, TVA et total TTC calculés automatiquement",
        TemplateGroup.MONEY, sheet = true,
        profile + listOf(
            Field("siret", "SIRET", "Facultatif"),
            Field("client", "Client"),
            Field("adresse_client", "Adresse du client", multiline = true),
            Field("numero", if (quote) "N° du devis" else "N° de facture", "Ex. : 2026-001"),
            Field("tva", "Taux de TVA (%)", "0 si vous êtes auto-entrepreneur sans TVA", default = "20")
        )
    ) { v ->
        val kind = if (quote) "DEVIS" else "FACTURE"
        val s = SheetBuilder(if (quote) "Devis" else "Facture")
        s.put("A1", v["nom"], titleStyle)
        s.put("A2", v["adresse"])
        s.put("A3", v["ville"])
        s.put("A4", "${v["telephone"]} · ${v["email"]}")
        if (v.has("siret")) s.put("A5", "SIRET : ${v["siret"]}")

        s.put("D1", kind, CellStyle(bold = true, color = ACCENT, align = 3))
        s.put("D2", "N° ${if (v.has("numero")) v["numero"] else "${v.year}-001"}", rightStyle)
        s.put("D3", "Date : ${v.shortDate()}", rightStyle)
        s.put("D4", if (quote) "Valable jusqu'au ${v.shortDate(30)}" else "Échéance : ${v.shortDate(30)}", rightStyle)

        s.put("A7", if (quote) "Client :" else "Facturé à :", bold)
        s.put("A8", v["client"], bold)
        v.lines("adresse_client").take(3).forEachIndexed { i, line -> s.put("A${9 + i}", line) }

        val h = FIRST_LINE - 1
        s.put("A$h", "Désignation", headerStyle)
        s.put("B$h", "Quantité", headerStyle)
        s.put("C$h", "Prix unitaire HT", headerStyle)
        s.put("D$h", "Total HT", headerStyle)
        for (row in FIRST_LINE..LAST_LINE) {
            s.put("D$row", "=SI(B$row=\"\";\"\";TEXTE(B$row*C$row;\"# ##0.00\"))", rightStyle)
        }
        // Une ligne d'exemple, pour voir le calcul fonctionner tout de suite.
        s.put("A$FIRST_LINE", "Prestation (à remplacer)")
        s.put("B$FIRST_LINE", "1")
        s.put("C$FIRST_LINE", "100")

        val ht = "SOMMEPROD(B$FIRST_LINE:B$LAST_LINE;C$FIRST_LINE:C$LAST_LINE)"
        val t = LAST_LINE + 2
        val rate = "C${t + 1}"
        s.put("C$t", "Total HT", totalStyle)
        s.put("D$t", euros(ht), totalStyle)
        s.put("B${t + 1}", "Taux de TVA (%)", rightStyle)
        s.put(rate, v["tva"].replace("%", "").trim(), rightStyle)
        s.put("C${t + 2}", "TVA", totalStyle)
        s.put("D${t + 2}", euros("$ht*$rate/100"), totalStyle)
        s.put("C${t + 3}", "Total TTC", totalStyle.copy(background = HEADER_FILL))
        s.put("D${t + 3}", euros("$ht*(1+$rate/100)"), totalStyle.copy(background = HEADER_FILL))

        var n = t + 5
        if (v["tva"].replace("%", "").trim().replace(',', '.').toDoubleOrNull() == 0.0) {
            s.put("A$n", "TVA non applicable, art. 293 B du CGI", noteStyle); n++
        }
        if (quote) {
            s.put("A$n", "Devis valable 30 jours. Paiement : 30 % à la commande, le solde à la livraison.", noteStyle); n += 2
            s.put("A$n", "Bon pour accord — date et signature du client :", bold)
        } else {
            s.put("A$n", "Paiement à réception par virement. Pas d'escompte pour paiement anticipé.", noteStyle); n++
            s.put(
                "A$n",
                "En cas de retard : pénalités au taux d'intérêt légal majoré de 10 points et indemnité forfaitaire de 40 € pour frais de recouvrement.",
                noteStyle
            )
        }
        val numero = if (v.has("numero")) v["numero"] else "${v.year}-001"
        Built.Sheet(Workbook(s.name, listOf(s.build(columns = 6, rows = n + 10))), "${s.name} $numero ${if (v.has("client")) v["client"] else ""}".trim())
    }

    const val INCOME_FIRST = 5
    private val incomes = listOf("Salaire", "Aides (CAF, APL…)", "Autres revenus")
    private val expenses = listOf(
        "Loyer ou crédit", "Électricité, gaz, eau", "Internet et téléphone", "Assurances", "Courses",
        "Transports", "Santé", "Abonnements", "Loisirs et sorties", "Épargne", "Imprévus"
    )

    private fun budget() = Template(
        "budget", "Budget du mois", "Revenus, dépenses prévues et réelles, reste à vivre calculé",
        TemplateGroup.MONEY, sheet = true,
        listOf(Field("mois", "Mois", default = ""))
    ) { v ->
        val month = if (v.has("mois")) v["mois"] else v.date().substringAfter(' ').replaceFirstChar { it.uppercase() }
        val s = SheetBuilder("Budget")
        s.put("A1", "Budget — $month", titleStyle)
        s.put("A2", "Remplis les colonnes « Prévu » et « Réel » : les totaux se calculent seuls.", noteStyle)

        fun header(row: Int, title: String) {
            s.put("A$row", title, headerStyle)
            s.put("B$row", "Prévu", headerStyle)
            s.put("C$row", "Réel", headerStyle)
            s.put("D$row", "Écart", headerStyle)
        }

        header(INCOME_FIRST - 1, "Revenus")
        incomes.forEachIndexed { i, label -> s.put("A${INCOME_FIRST + i}", label) }
        val incomeLast = INCOME_FIRST + incomes.size - 1
        val incomeTotal = incomeLast + 1
        s.put("A$incomeTotal", "Total revenus", totalStyle.copy(align = 0))

        val expenseHeader = incomeTotal + 2
        header(expenseHeader, "Dépenses")
        val expenseFirst = expenseHeader + 1
        expenses.forEachIndexed { i, label -> s.put("A${expenseFirst + i}", label) }
        val expenseLast = expenseFirst + expenses.size - 1
        val expenseTotal = expenseLast + 1
        s.put("A$expenseTotal", "Total dépenses", totalStyle.copy(align = 0))

        for (row in (INCOME_FIRST..incomeLast) + (expenseFirst..expenseLast)) {
            s.put("D$row", "=SI(ET(B$row=\"\";C$row=\"\");\"\";C$row-B$row)", rightStyle)
        }
        for (col in listOf("B", "C")) {
            s.put("$col$incomeTotal", euros("SOMME(${col}$INCOME_FIRST:${col}$incomeLast)"), totalStyle)
            s.put("$col$expenseTotal", euros("SOMME(${col}$expenseFirst:${col}$expenseLast)"), totalStyle)
        }

        val rest = expenseTotal + 2
        s.put("A$rest", "Reste à vivre", totalStyle.copy(align = 0, background = HEADER_FILL))
        for (col in listOf("B", "C")) {
            s.put(
                "$col$rest",
                euros("SOMME(${col}$INCOME_FIRST:${col}$incomeLast)-SOMME(${col}$expenseFirst:${col}$expenseLast)"),
                totalStyle.copy(background = HEADER_FILL)
            )
        }
        s.put("A${rest + 1}", "Part des revenus dépensée", rightStyle.copy(align = 0))
        s.put(
            "C${rest + 1}",
            // Sans revenus saisis, la case reste vide plutôt que d'afficher une erreur.
            "=SIERREUR(TEXTE(SOMME(C$expenseFirst:C$expenseLast)/SOMME(C$INCOME_FIRST:C$incomeLast);\"0 %\");\"\")",
            rightStyle
        )
        Built.Sheet(Workbook("Budget $month", listOf(s.build(columns = 6, rows = rest + 12))), "Budget $month")
    }

    /** Pour les tests : la cellule « Total TTC » d'une facture ou d'un devis. */
    internal fun totalRef(label: String): String = when (label) {
        "HT" -> "D${LAST_LINE + 2}"
        "TVA" -> "D${LAST_LINE + 4}"
        else -> "D${LAST_LINE + 5}"
    }
}
