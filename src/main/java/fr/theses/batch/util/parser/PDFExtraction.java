package fr.theses.batch.util.parser;

import java.util.List;

/**
 * Résultat de l'extraction du texte d'un PDF.
 *
 * @param pages           Pages lues, avec leur numéro et leur texte
 * @param totalPages      Nombre total de pages du document
 * @param glyphs          Nombre de glyphes affichés sur les pages lues
 * @param lostGlyphs      Glyphes sans caractère Unicode connu, absents du texte extrait
 * @param recoveredGlyphs Glyphes dont le caractère a été reconstitué via les polices système
 */
public record PDFExtraction(List<PDFTextExtractor.PDFPage> pages, int totalPages,
                            int glyphs, int lostGlyphs, int recoveredGlyphs) {

    public double lostPercent() {
        return percent(lostGlyphs);
    }

    public double recoveredPercent() {
        return percent(recoveredGlyphs);
    }

    private double percent(int count) {
        return glyphs == 0 ? 0 : 100.0 * count / glyphs;
    }
}
