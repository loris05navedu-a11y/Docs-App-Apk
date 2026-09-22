package engine

// FunctionHelp décrit une fonction pour l'assistant.
type FunctionHelp struct {
	Signature   string `json:"signature"`
	Description string `json:"description"`
}

// Name rend le nom seul, sans les paramètres.
func (f FunctionHelp) Name() string {
	for i, c := range f.Signature {
		if c == '(' {
			return f.Signature[:i]
		}
	}
	return f.Signature
}

// FunctionGroup rassemble les fonctions d'une même famille.
type FunctionGroup struct {
	Title   string         `json:"title"`
	Entries []FunctionHelp `json:"entries"`
}

// Catalog est l'aide affichée dans l'assistant de formules.
var Catalog = []FunctionGroup{
	{"Maths", []FunctionHelp{
		{"SOMME(plage)", "Additionne les nombres"},
		{"PRODUIT(plage)", "Multiplie les nombres"},
		{"ABS(n)", "Valeur absolue"},
		{"ARRONDI(n;décimales)", "Arrondit au plus proche"},
		{"ARRONDI.SUP(n;décimales)", "Arrondit au-dessus"},
		{"ARRONDI.INF(n;décimales)", "Arrondit au-dessous"},
		{"ENT(n)", "Partie entière"},
		{"TRONQUE(n;décimales)", "Tronque sans arrondir"},
		{"MOD(n;diviseur)", "Reste de la division"},
		{"RACINE(n)", "Racine carrée"},
		{"PUISSANCE(n;exposant)", "Élève à une puissance"},
		{"EXP(n)", "Exponentielle"},
		{"LN(n)", "Logarithme népérien"},
		{"LOG(n;base)", "Logarithme"},
		{"SIGNE(n)", "-1, 0 ou 1"},
		{"PLAFOND(n;pas)", "Arrondit au multiple supérieur"},
		{"PLANCHER(n;pas)", "Arrondit au multiple inférieur"},
		{"PAIR(n)", "Arrondit au pair supérieur"},
		{"IMPAIR(n)", "Arrondit à l'impair supérieur"},
		{"FACT(n)", "Factorielle"},
		{"PGCD(plage)", "Plus grand commun diviseur"},
		{"PPCM(plage)", "Plus petit commun multiple"},
		{"SOMMEPROD(p1;p2)", "Somme des produits"},
		{"ALEA()", "Nombre entre 0 et 1"},
		{"ALEA.ENTRE.BORNES(a;b)", "Entier au hasard"},
	}},
	{"Statistiques", []FunctionHelp{
		{"MOYENNE(plage)", "Moyenne des nombres"},
		{"MIN(plage)", "Plus petite valeur"},
		{"MAX(plage)", "Plus grande valeur"},
		{"NB(plage)", "Compte les nombres"},
		{"NBVAL(plage)", "Compte les cases remplies"},
		{"NB.VIDE(plage)", "Compte les cases vides"},
		{"MEDIANE(plage)", "Valeur médiane"},
		{"MODE(plage)", "Valeur la plus fréquente"},
		{"ECARTYPE(plage)", "Écart-type d'un échantillon"},
		{"ECARTYPEP(plage)", "Écart-type d'une population"},
		{"VAR(plage)", "Variance d'un échantillon"},
		{"GRANDE.VALEUR(plage;k)", "k-ième plus grande"},
		{"PETITE.VALEUR(plage;k)", "k-ième plus petite"},
		{"RANG(n;plage;ordre)", "Rang d'une valeur"},
		{"CENTILE(plage;p)", "Centile"},
		{"QUARTILE(plage;n)", "Quartile 0 à 4"},
	}},
	{"Conditions", []FunctionHelp{
		{"SI(test;alors;sinon)", "Choisit selon un test"},
		{"SI.CONDITIONS(t1;v1;t2;v2)", "Premier test vrai"},
		{"SIERREUR(valeur;secours)", "Remplace une erreur"},
		{"ET(a;b)", "Vrai si tout est vrai"},
		{"OU(a;b)", "Vrai si l'un est vrai"},
		{"NON(a)", "Inverse un test"},
		{"SOMME.SI(plage;critère;somme)", "Somme sous condition"},
		{"NB.SI(plage;critère)", "Compte sous condition"},
		{"MOYENNE.SI(plage;critère;moy)", "Moyenne sous condition"},
		{"ESTVIDE(valeur)", "Vrai si la case est vide"},
		{"ESTNUM(valeur)", "Vrai si c'est un nombre"},
		{"ESTTEXTE(valeur)", "Vrai si c'est du texte"},
	}},
	{"Texte", []FunctionHelp{
		{"CONCATENER(a;b)", "Colle des textes"},
		{"GAUCHE(texte;n)", "n premiers caractères"},
		{"DROITE(texte;n)", "n derniers caractères"},
		{"STXT(texte;début;n)", "Extrait au milieu"},
		{"NBCAR(texte)", "Nombre de caractères"},
		{"MAJUSCULE(texte)", "Tout en majuscules"},
		{"MINUSCULE(texte)", "Tout en minuscules"},
		{"NOMPROPRE(texte)", "Initiales en majuscule"},
		{"SUPPRESPACE(texte)", "Enlève les espaces en trop"},
		{"SUBSTITUE(texte;ancien;nouveau)", "Remplace un motif"},
		{"REMPLACER(texte;début;n;nouveau)", "Remplace une portion"},
		{"TROUVE(cherché;texte)", "Position, casse respectée"},
		{"CHERCHE(cherché;texte)", "Position, casse ignorée"},
		{"REPT(texte;n)", "Répète un texte"},
		{"JOINDRE.TEXTE(sép;ignorer;plage)", "Assemble une plage"},
		{"TEXTE(valeur;format)", "Met en forme un nombre"},
		{"CNUM(texte)", "Convertit en nombre"},
		{"EXACT(a;b)", "Compare, casse comprise"},
	}},
	{"Recherche", []FunctionHelp{
		{"RECHERCHEV(valeur;plage;colonne;approché)", "Cherche dans la 1re colonne"},
		{"RECHERCHEH(valeur;plage;ligne;approché)", "Cherche dans la 1re ligne"},
		{"INDEX(plage;ligne;colonne)", "Valeur à une position"},
		{"EQUIV(valeur;plage;type)", "Position d'une valeur"},
		{"CHOISIR(n;a;b)", "n-ième argument"},
		{"LIGNES(plage)", "Nombre de lignes"},
		{"COLONNES(plage)", "Nombre de colonnes"},
	}},
	{"Date et heure", []FunctionHelp{
		{"AUJOURDHUI()", "Date du jour"},
		{"MAINTENANT()", "Date et heure"},
		{"DATE(année;mois;jour)", "Construit une date"},
		{"ANNEE(date)", "Année d'une date"},
		{"MOIS(date)", "Mois d'une date"},
		{"JOUR(date)", "Jour d'une date"},
		{"JOURSEM(date)", "Jour de la semaine"},
		{"JOURS(fin;début)", "Nombre de jours"},
		{"MOIS.DECALER(date;n)", "Décale de n mois"},
		{"FIN.MOIS(date;n)", "Dernier jour du mois"},
	}},
	{"Finance", []FunctionHelp{
		{"VPM(taux;durée;capital)", "Mensualité d'un prêt"},
		{"VC(taux;durée;versement;capital)", "Valeur future"},
		{"VA(taux;durée;versement;valeur)", "Valeur actuelle"},
		{"NPM(taux;versement;capital)", "Nombre de périodes"},
	}},
}
