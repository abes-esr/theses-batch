package fr.theses.batch.util.log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Met en forme le détail des avertissements comptés par {@link PdfWarningCollector}, une ligne par type.
 * <p>
 * Les types qui ne diffèrent que par la police (« No Unicode mapping in font X », « Using fallback
 * font LiberationSans for X »…) tiennent sur une seule ligne, avec le total et les polices les plus
 * touchées : « 1100 x PDSimpleFont - No Unicode mapping in font F18 (220), F51 (203) et 17 autres ».
 * Le niveau n'est écrit que s'il n'est pas WARN, pour qu'un grep sur WARN ne ramène que les en-têtes.
 */
public final class PdfWarningSummary {

    /** Polices citées au plus sur une ligne regroupée */
    static final int MAX_FONTS = 5;

    /** Partie commune du message, puis nom de la police */
    private static final Pattern BY_FONT = Pattern.compile("^(.+ - (?:No Unicode mapping in font"
            + "|No PostScript name data is provided for the font"
            + "|Using fallback font \\S+ for(?: CID-keyed TrueType font)?)) (.+)$");

    private PdfWarningSummary() {
    }

    /**
     * @param warnings Nombre d'avertissements par type
     * @return Lignes du détail par nombre décroissant, sans retour à la ligne
     */
    public static List<String> lines(List<Map.Entry<String, Integer>> warnings) {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> warning : warnings) {
            Matcher byFont = BY_FONT.matcher(warning.getKey());
            String label = byFont.matches() ? byFont.group(1) : warning.getKey();
            String font = byFont.matches() ? byFont.group(2) : "";
            groups.computeIfAbsent(label, key -> new LinkedHashMap<>()).merge(font, warning.getValue(), Integer::sum);
        }

        return groups.entrySet().stream()
                .map(group -> Map.entry(group, total(group.getValue())))
                .sorted(Map.Entry.<Map.Entry<String, Map<String, Integer>>, Integer>comparingByValue().reversed())
                .map(group -> String.format(Locale.ROOT, "%5d x %s", group.getValue(),
                        withoutWarnLevel(group.getKey().getKey()) + fontList(group.getKey().getValue())))
                .toList();
    }

    private static int total(Map<String, Integer> fonts) {
        return fonts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String withoutWarnLevel(String type) {
        return type.startsWith("WARN ") ? type.substring("WARN ".length()) : type;
    }

    private static String fontList(Map<String, Integer> fonts) {
        if (fonts.size() == 1) {
            String font = fonts.keySet().iterator().next();
            return font.isEmpty() ? "" : " " + font;
        }
        List<String> cited = fonts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_FONTS)
                .map(font -> font.getKey() + " (" + font.getValue() + ")")
                .toList();
        String others = fonts.size() > MAX_FONTS ? " et " + (fonts.size() - MAX_FONTS) + " autres" : "";
        return " " + String.join(", ", cited) + others;
    }
}
