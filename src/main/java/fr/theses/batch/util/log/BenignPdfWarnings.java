package fr.theses.batch.util.log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Liste fermée des types d'avertissements PDFBox / FontBox sans conséquence sur le texte extrait,
 * exprimés dans le format de {@link PdfWarningCollector} (« NIVEAU Classe - message normalisé »).
 * <p>
 * Un avertissement bénin reste compté et affiché dans le récapitulatif du fichier, mais ne suffit
 * pas à le faire journaliser en WARN.
 * <p>
 * À part, {@link #isMeasuredByLostText} : les avertissements dont l'effet est exactement le texte
 * perdu déjà mesuré par le processor. Ils ne sont pas bénins, mais c'est la part de texte perdu, et
 * non leur présence, qui fixe le niveau du récapitulatif.
 */
public final class BenignPdfWarnings {

    /**
     * Vérifiés sur les sources de PDFBox 3.0.8.
     */
    private static final List<Pattern> TYPES = List.of(
            // Remplacement d'une police simple non embarquée par une police système : le texte vient de
            // /ToUnicode ou de l'encodage, les largeurs de /Widths ou, pour les 14 polices standard
            // (ArialMT, TimesNewRoman… par alias), de leur AFM. Seule une police hors standard sans
            // /Widths, contraire à la norme, prendrait les largeurs de la police de remplacement.
            // Le remplacement d'une police CID (PDCIDFontType2, « … for CID-keyed TrueType font … ») n'en
            // fait pas partie : sans /ToUnicode, PDFBox lit alors les caractères dans la cmap de la police
            // de remplacement, et le texte extrait peut être faux.
            Pattern.compile("WARN (?:PDTrueTypeFont|PDType1Font|PDType1CFont) - Using fallback font .+"),
            // Table « post » sans noms de glyphes (format 3), permis pour une police TrueType
            Pattern.compile("WARN PostScriptTable - No PostScript name data is provided for the font .+"),
            // Sous-table de crénage ignorée : l'extraction de texte ne se sert pas du crénage
            Pattern.compile("WARN KerningSubtable - Kerning sub-table too short, got N bytes, expect N or more\\."),
            // Contour d'un glyphe, sans rapport avec son caractère
            Pattern.compile("WARN Type1CharString - closepath without initial moveTo in font .+"),
            // /BaseEncoding d'une police Type 3, hors norme : PDFBox l'applique quand même, comme les
            // autres lecteurs (PDFBOX-5963)
            Pattern.compile("WARN DictionaryEncoding - /BaseEncoding in type N font"),
            // Découpage d'une image intégrée au flux de la page
            Pattern.compile("WARN PDFStreamParser - ignoring 'EI' assumed to be in the middle of inline image .+"),
            // État graphique absent des ressources (TikZ/pgf) : l'opérateur est ignoré
            Pattern.compile("ERROR SetGraphicsStateParameters - name for 'gs' operator not found in resources: .+"),
            // Table de références croisées mal placée, réparée par PDFBox
            Pattern.compile("WARN COSParser - /XRefStm offset N is incorrect, corrected to N"),
            // Table /CIDToGIDMap tronquée par l'application (CidToGidMapLimiter) : PDFBox n'aurait jamais
            // lu les octets retirés, le texte est identique (vérifié sur 2016PA066214.pdf)
            Pattern.compile("WARN SystemFontUnicodeRecovery - Table /CIDToGIDMap démesurée tronquée, police .+"));

    /** Glyphes sans caractère Unicode, un avertissement par code et par police */
    private static final Pattern MEASURED_BY_LOST_TEXT = Pattern.compile("WARN \\w+ - No Unicode mapping in font .+");

    private BenignPdfWarnings() {
    }

    /**
     * @param type Type d'avertissement tel que compté par {@link PdfWarningCollector}
     * @return true si ce type est sans conséquence sur le texte extrait
     */
    public static boolean isBenign(String type) {
        return TYPES.stream().anyMatch(pattern -> pattern.matcher(type).matches());
    }

    /**
     * @param type Type d'avertissement tel que compté par {@link PdfWarningCollector}
     * @return true si l'effet de ce type sur le texte est déjà compté dans les glyphes perdus
     */
    public static boolean isMeasuredByLostText(String type) {
        return MEASURED_BY_LOST_TEXT.matcher(type).matches();
    }
}
