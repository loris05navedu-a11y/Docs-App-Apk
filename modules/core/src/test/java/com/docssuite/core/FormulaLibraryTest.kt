package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Toutes les familles de fonctions du tableur, vérifiées sur des cas concrets. */
class FormulaLibraryTest {

    private val sheet = mapOf(
        // Un petit catalogue : produit, catégorie, prix, stock.
        "A1" to "Pomme", "B1" to "Fruit", "C1" to "2.5", "D1" to "10",
        "A2" to "Pain", "B2" to "Boulangerie", "C2" to "1.2", "D2" to "4",
        "A3" to "Poire", "B3" to "Fruit", "C3" to "3", "D3" to "7",
        "A4" to "Lait", "B4" to "Crèmerie", "C4" to "0.9", "D4" to "0"
    )

    private fun text(formula: String, cells: Map<String, String> = sheet): String =
        FormulaEngine.evaluate(formula, cells)!!.asText()

    private fun number(formula: String, cells: Map<String, String> = sheet): Double =
        FormulaEngine.evaluate(formula, cells)!!.asNumber()!!

    // ------------------------------------------------------------- texte

    @Test
    fun `les fonctions de texte decoupent et assemblent`() {
        assertEquals("Pom", text("""GAUCHE(A1;3)"""))
        assertEquals("me", text("""DROITE(A1;2)"""))
        assertEquals("omm", text("""STXT(A1;2;3)"""))
        assertEquals(5.0, number("""NBCAR(A1)"""), 0.0)
        assertEquals("POMME", text("""MAJUSCULE(A1)"""))
        assertEquals("pomme", text("""MINUSCULE(A1)"""))
        assertEquals("Jean Dupont", text("""NOMPROPRE("jean DUPONT")"""))
        assertEquals("a b", text("""SUPPRESPACE("  a    b  ")"""))
        assertEquals("Pomme-Fruit", text("""CONCATENER(A1;"-";B1)"""))
        assertEquals("Pomme-Fruit", text("""A1&"-"&B1"""))
        assertEquals("Pomme de terre", text("""SUBSTITUE("Pomme de air";"air";"terre")"""))
        assertEquals("abcabc", text("""REPT("abc";2)"""))
        assertEquals(3.0, number("""TROUVE("m";A1)"""), 0.0)
        assertEquals("#N/A", text("""TROUVE("z";A1)"""))
        assertEquals("Pomme;Pain", text("""JOINDRE.TEXTE(";";VRAI;A1:A2)"""))
    }

    @Test
    fun `le texte et les nombres se comparent sans se confondre`() {
        assertEquals("VRAI", text("""EXACT("a";"a")"""))
        assertEquals("FAUX", text("""EXACT("a";"A")"""))
        assertEquals("VRAI", text("""ESTTEXTE(A1)"""))
        assertEquals("FAUX", text("""ESTNUM(A1)"""))
        assertEquals("VRAI", text("""ESTNUM(C1)"""))
        assertEquals("VRAI", text("""ESTVIDE(Z9)"""))
    }

    // --------------------------------------------------------- conditions

    @Test
    fun `les agregats conditionnels filtrent sur une autre plage`() {
        assertEquals(2.0, number("""NB.SI(B1:B4;"Fruit")"""), 0.0)
        assertEquals(5.5, number("""SOMME.SI(B1:B4;"Fruit";C1:C4)"""), 1e-9)
        assertEquals(2.75, number("""MOYENNE.SI(B1:B4;"Fruit";C1:C4)"""), 1e-9)
        assertEquals(2.0, number("""NB.SI(D1:D4;">5")"""), 0.0)
        assertEquals(3.0, number("""NB.SI(D1:D4;"<>0")"""), 0.0)
        assertEquals(2.0, number("""NB.SI(A1:A4;"Po*")"""), 0.0)
    }

    @Test
    fun `SIERREUR intercepte une erreur au lieu de la propager`() {
        assertEquals("#DIV/0!", text("""1/0"""))
        assertEquals("secours", text("""SIERREUR(1/0;"secours")"""))
        assertEquals(4.0, number("""SIERREUR(2+2;"secours")"""), 0.0)
        assertEquals("VRAI", text("""ESTERREUR(1/0)"""))
    }

    @Test
    fun `les tests logiques se combinent`() {
        assertEquals("VRAI", text("""ET(1=1;2>1)"""))
        assertEquals("FAUX", text("""ET(1=1;2<1)"""))
        assertEquals("VRAI", text("""OU(1=2;2>1)"""))
        assertEquals("VRAI", text("""NON(1=2)"""))
        assertEquals("cher", text("""SI(C3>2;"cher";"bon marché")"""))
        assertEquals("moyen", text("""SI.CONDITIONS(C1>10;"cher";C1>2;"moyen";VRAI;"bas")"""))
    }

    // ---------------------------------------------------------- recherche

    @Test
    fun `RECHERCHEV retrouve une ligne du catalogue`() {
        // Colonne 3 de A1:D4 = le prix.
        assertEquals(3.0, number("""RECHERCHEV("Poire";A1:D4;3;FAUX)"""), 1e-9)
        assertEquals("Crèmerie", text("""RECHERCHEV("Lait";A1:D4;2;FAUX)"""))
        assertEquals("#N/A", text("""RECHERCHEV("Kiwi";A1:D4;2;FAUX)"""))
    }

    @Test
    fun `INDEX et EQUIV se combinent comme dans un tableur`() {
        assertEquals(3.0, number("""EQUIV("Poire";A1:A4;0)"""), 0.0)
        assertEquals("Poire", text("""INDEX(A1:A4;3)"""))
        assertEquals(3.0, number("""INDEX(A1:D4;3;3)"""), 1e-9)
        assertEquals(1.2, number("""INDEX(C1:C4;EQUIV("Pain";A1:A4;0))"""), 1e-9)
        assertEquals("Pain", text("""CHOISIR(2;A1;A2;A3)"""))
        assertEquals(4.0, number("""LIGNES(A1:D4)"""), 0.0)
        assertEquals(4.0, number("""COLONNES(A1:D4)"""), 0.0)
    }

    // ------------------------------------------------------------- dates

    @Test
    fun `les dates se construisent et se decomposent`() {
        val serial = number("""DATE(2024;3;15)""")
        assertEquals(2024.0, number("""ANNEE(DATE(2024;3;15))"""), 0.0)
        assertEquals(3.0, number("""MOIS(DATE(2024;3;15))"""), 0.0)
        assertEquals(15.0, number("""JOUR(DATE(2024;3;15))"""), 0.0)
        assertEquals(31.0, number("""JOURS(DATE(2024;4;15);DATE(2024;3;15))"""), 0.0)
        assertEquals(45366.0, serial, 0.0)
        assertEquals(4.0, number("""MOIS(MOIS.DECALER(DATE(2024;3;15);1))"""), 0.0)
        assertEquals(31.0, number("""JOUR(FIN.MOIS(DATE(2024;3;15);0))"""), 0.0)
        assertEquals("15/03/2024", text("""TEXTE(DATE(2024;3;15);"jj/mm/aaaa")"""))
    }

    // ------------------------------------------------------------- maths

    @Test
    fun `les arrondis se distinguent les uns des autres`() {
        assertEquals(2.35, number("""ARRONDI(2.345;2)"""), 1e-9)
        assertEquals(2.4, number("""ARRONDI.SUP(2.31;1)"""), 1e-9)
        assertEquals(2.3, number("""ARRONDI.INF(2.39;1)"""), 1e-9)
        assertEquals(2.0, number("""TRONQUE(2.99)"""), 1e-9)
        assertEquals(10.0, number("""PLAFOND(7;5)"""), 1e-9)
        assertEquals(5.0, number("""PLANCHER(7;5)"""), 1e-9)
        assertEquals(4.0, number("""PAIR(3.1)"""), 1e-9)
        assertEquals(5.0, number("""IMPAIR(3.1)"""), 1e-9)
        assertEquals(2.0, number("""MOD(-4;3)"""), 1e-9)
        assertEquals(120.0, number("""FACT(5)"""), 1e-9)
        assertEquals(6.0, number("""PGCD(12;18)"""), 1e-9)
        assertEquals(36.0, number("""PPCM(12;18)"""), 1e-9)
        assertEquals(0.5, number("""50%"""), 1e-9)
    }

    @Test
    fun `SOMMEPROD multiplie les plages terme a terme`() {
        // Prix × stock : 2,5×10 + 1,2×4 + 3×7 + 0,9×0
        assertEquals(50.8, number("""SOMMEPROD(C1:C4;D1:D4)"""), 1e-9)
    }

    // -------------------------------------------------------- statistiques

    @Test
    fun `les statistiques ignorent le texte`() {
        assertEquals(4.0, number("""NB(C1:C4)"""), 0.0)
        assertEquals(4.0, number("""NBVAL(A1:A4)"""), 0.0)
        assertEquals(0.0, number("""NB(A1:A4)"""), 0.0)
        assertEquals(3.0, number("""GRANDE.VALEUR(C1:C4;1)"""), 1e-9)
        assertEquals(0.9, number("""PETITE.VALEUR(C1:C4;1)"""), 1e-9)
        assertEquals(2.0, number("""RANG(2.5;C1:C4)"""), 0.0)
        assertEquals(1.85, number("""MEDIANE(C1:C4)"""), 1e-9)
    }

    @Test
    fun `NB VIDE compte les cases reellement vides`() {
        val partial = mapOf("A1" to "1", "A3" to "3")
        assertEquals(1.0, number("""NB.VIDE(A1:A3)""", partial), 0.0)
        assertEquals(2.0, number("""NBVAL(A1:A3)""", partial), 0.0)
    }

    // ----------------------------------------------------------- finance

    @Test
    fun `VPM donne la mensualite d un pret`() {
        // 10 000 € sur 12 mois à 0,5 % par mois.
        assertEquals(-860.66, number("""VPM(0.005;12;10000)"""), 0.01)
        assertEquals(-1000.0, number("""VPM(0;10;10000)"""), 0.01)
    }

    // -------------------------------------------------------- régressions

    @Test
    fun `les anciennes formules numeriques fonctionnent toujours`() {
        assertEquals(14.0, FormulaEngine.evaluateExpression("2+3*4")!!, 1e-9)
        assertEquals(4.0, FormulaEngine.evaluateExpression("RACINE(16)")!!, 1e-9)
        assertEquals(6.0, FormulaEngine.evaluateExpression("SOMME(A1;A2)", mapOf("A1" to "2", "A2" to "4"))!!, 1e-9)
        assertEquals(null, FormulaEngine.evaluateExpression("1/0"))
        assertEquals(null, FormulaEngine.evaluateExpression("((("))
        assertEquals(null, FormulaEngine.evaluateExpression(""))
    }

    @Test
    fun `une reference circulaire reste signalee`() {
        val cycle = mapOf("A1" to "=A2", "A2" to "=A1")
        assertEquals("#CYCLE", FormulaEngine.displayValue("A1", cycle))
    }

    @Test
    fun `le catalogue d aide couvre toutes les familles`() {
        assertEquals(7, FormulaEngine.CATALOG.size)
        assertTrue(FormulaEngine.FUNCTIONS.size > 80)
        FormulaEngine.CATALOG.flatMap { it.entries }.forEach { entry ->
            assertTrue("Signature vide", entry.name.isNotBlank())
            assertTrue("Description vide pour ${entry.name}", entry.description.isNotBlank())
        }
    }

    @Test
    fun `chaque fonction du catalogue est reconnue par le moteur`() {
        val unknown = FormulaEngine.CATALOG.flatMap { it.entries }.map { it.name }.filter { name ->
            FormulaEngine.evaluate("$name()", sheet)?.asText() == "#NOM?"
        }
        assertEquals("Fonctions annoncées mais absentes : $unknown", emptyList<String>(), unknown)
    }
}
