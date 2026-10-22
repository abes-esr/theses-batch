package fr.theses.batch.util.log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Liste fermée des types d'avertissements PDFBox / FontBox sans conséquence sur le texte extrait,
 * exprimés dans le format de {@link PdfWarningCollector} (« NIVEAU Classe - message normalisé »).
 * <p>
 * Un avertissement bénin reste compté et affiché dans le récapitulatif du fichier, mais ne suffit
 * pas à le faire journaliser en WARN.
 */
public final class BenignPdfWarnings {

    /**
     * Remplacement d'une police simple non embarquée par une police système (vérifié sur PDFBox 3.0.8) :
     * le texte vient de /ToUnicode ou de l'encodage, les largeurs de /Widths ou, pour les 14 polices
     * standard (ArialMT, TimesNewRoman… par alias), de leur AFM. Seule une police hors standard sans
     * /Widths, contraire à la norme, prendrait les largeurs de la police de remplacement.
     * <p>
     * Le remplacement d'une police CID (PDCIDFontType2, « … for CID-keyed TrueType font … ») n'en fait
     * pas partie : sans /ToUnicode, PDFBox lit alors les caractères dans la cmap de la police de
     * remplacement, et le texte extrait peut être faux.
     */
    private static final List<Pattern> TYPES = List.of(
            Pattern.compile("WARN (?:PDTrueTypeFont|PDType1Font|PDType1CFont) - Using fallback font .+"));

    private BenignPdfWarnings() {
    }

    /**
     * @param type Type d'avertissement tel que compté par {@link PdfWarningCollector}
     * @return true si ce type est sans conséquence sur le texte extrait
     */
    public static boolean isBenign(String type) {
        return TYPES.stream().anyMatch(pattern -> pattern.matcher(type).matches());
    }
}
