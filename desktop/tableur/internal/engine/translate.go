package engine

import "strings"

// Excel enregistre les noms de fonctions en anglais dans le fichier, quelle
// que soit la langue de l'interface. Une formule écrite « SOMME » s'ouvrirait
// donc en #NOM? : il faut la traduire à l'écriture, et revenir au français à
// la lecture.
var frenchToEnglish = map[string]string{
	"SOMME": "SUM", "PRODUIT": "PRODUCT", "ARRONDI": "ROUND",
	"ARRONDI.SUP": "ROUNDUP", "ARRONDI.INF": "ROUNDDOWN", "ENT": "INT",
	"TRONQUE": "TRUNC", "RACINE": "SQRT", "PUISSANCE": "POWER",
	"SIGNE": "SIGN", "PLAFOND": "CEILING", "PLANCHER": "FLOOR",
	"ARRONDI.AU.MULTIPLE": "MROUND", "PAIR": "EVEN", "IMPAIR": "ODD",
	"PGCD": "GCD", "PPCM": "LCM", "SOMME.CARRES": "SUMSQ",
	"SOMMEPROD": "SUMPRODUCT", "ALEA": "RAND",
	"ALEA.ENTRE.BORNES": "RANDBETWEEN", "DEGRES": "DEGREES",

	"MOYENNE": "AVERAGE", "NB": "COUNT", "NBVAL": "COUNTA",
	"NB.VIDE": "COUNTBLANK", "MEDIANE": "MEDIAN", "ECARTYPE": "STDEV",
	"ECARTYPEP": "STDEVP", "GRANDE.VALEUR": "LARGE",
	"PETITE.VALEUR": "SMALL", "RANG": "RANK", "CENTILE": "PERCENTILE",

	"SI": "IF", "SI.CONDITIONS": "IFS", "SIERREUR": "IFERROR",
	"ET": "AND", "OU": "OR", "NON": "NOT", "OUX": "XOR",
	"SOMME.SI": "SUMIF", "NB.SI": "COUNTIF", "MOYENNE.SI": "AVERAGEIF",
	"ESTVIDE": "ISBLANK", "ESTNUM": "ISNUMBER", "ESTTEXTE": "ISTEXT",
	"ESTERREUR": "ISERROR", "VRAI": "TRUE", "FAUX": "FALSE",

	"CONCATENER": "CONCAT", "GAUCHE": "LEFT", "DROITE": "RIGHT",
	"STXT": "MID", "NBCAR": "LEN", "MAJUSCULE": "UPPER",
	"MINUSCULE": "LOWER", "NOMPROPRE": "PROPER", "SUPPRESPACE": "TRIM",
	"SUBSTITUE": "SUBSTITUTE", "REMPLACER": "REPLACE", "TROUVE": "FIND",
	"CHERCHE": "SEARCH", "JOINDRE.TEXTE": "TEXTJOIN", "CNUM": "VALUE",
	"TEXTE": "TEXT",

	"RECHERCHEV": "VLOOKUP", "RECHERCHEH": "HLOOKUP", "EQUIV": "MATCH",
	"CHOISIR": "CHOOSE", "LIGNES": "ROWS", "COLONNES": "COLUMNS",

	"AUJOURDHUI": "TODAY", "MAINTENANT": "NOW", "ANNEE": "YEAR",
	"MOIS": "MONTH", "JOUR": "DAY", "JOURSEM": "WEEKDAY", "JOURS": "DAYS",
	"HEURE": "HOUR", "MOIS.DECALER": "EDATE", "FIN.MOIS": "EOMONTH",

	"VPM": "PMT", "VC": "FV", "VA": "PV", "NPM": "NPER",
	"CAR": "CHAR",
}

var englishToFrench = func() map[string]string {
	out := make(map[string]string, len(frenchToEnglish))
	for fr, en := range frenchToEnglish {
		out[en] = fr
	}
	return out
}()

// ToEnglish traduit les noms de fonctions d'une formule vers l'anglais.
func ToEnglish(formula string) string { return translate(formula, frenchToEnglish) }

// ToFrench fait le chemin inverse.
func ToFrench(formula string) string { return translate(formula, englishToFrench) }

// translate ne touche qu'aux noms suivis d'une parenthèse, et saute le texte
// entre guillemets : « SI » dans un libellé doit rester tel quel.
func translate(formula string, table map[string]string) string {
	if !strings.HasPrefix(formula, "=") {
		return formula
	}
	var out strings.Builder
	out.WriteByte('=')
	i := 1
	for i < len(formula) {
		ch := formula[i]
		if ch == '"' {
			end := strings.IndexByte(formula[i+1:], '"')
			if end < 0 {
				out.WriteString(formula[i:])
				break
			}
			out.WriteString(formula[i : i+end+2])
			i += end + 2
			continue
		}
		if isLetter(ch) {
			j := i
			for j < len(formula) {
				c := formula[j]
				if isLetter(c) || isDigit(c) || c == '.' || c == '_' {
					j++
					continue
				}
				break
			}
			name := formula[i:j]
			k := j
			for k < len(formula) && formula[k] == ' ' {
				k++
			}
			upper := strings.ToUpper(name)
			// VRAI et FAUX s'écrivent sans parenthèses : ce sont des
			// constantes, mais elles se traduisent comme les fonctions.
			isCall := k < len(formula) && formula[k] == '('
			isConstant := upper == "VRAI" || upper == "FAUX" ||
				upper == "TRUE" || upper == "FALSE"
			if isCall || isConstant {
				if replacement, found := table[upper]; found {
					out.WriteString(replacement)
					i = j
					continue
				}
			}
			out.WriteString(name)
			i = j
			continue
		}
		out.WriteByte(ch)
		i++
	}
	return out.String()
}
