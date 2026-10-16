package fr.theses.batch.util.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service pour le traitement des fichiers PDF
 * Utilise Apache PDFBox pour extraire le texte page par page
 */
@Service
@Slf4j
public class PDFTextExtractor {

    private final SystemFontUnicodeRecovery unicodeRecovery;

    public PDFTextExtractor(SystemFontUnicodeRecovery unicodeRecovery) {
        this.unicodeRecovery = unicodeRecovery;
    }

    /**
     * Extrait le texte d'un fichier PDF avec un maximum de pages.
     * Les polices sans correspondance Unicode sont d'abord complétées via les polices système
     * (voir {@link SystemFontUnicodeRecovery}), et les glyphes perdus sont comptés.
     * <p>
     * Si la récupération des polices échoue (PDF malformé), l'extraction continue
     * sans le recovery, qui est une optimisation et non une nécessité.
     *
     * @param filePath Chemin du fichier PDF
     * @param maxPages Nombre maximum de pages à lire (0 pour toutes)
     * @return Pages lues, nombre total de pages et statistiques sur les glyphes
     * @throws IOException En cas d'erreur de lecture
     */
    public PDFExtraction extractTextFromPdf(String filePath, int maxPages) throws IOException {
        List<PDFPage> pages = new ArrayList<>();

        if (filePath == null || !new File(filePath).exists()) {
            throw new IOException("File not found: " + filePath);
        }

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(new File(filePath)))) {
            int pageCount = document.getNumberOfPages();
            int pagesToRead = maxPages > 0 ? Math.min(maxPages, pageCount) : pageCount;

            // Tentative de recovery des polices (optimisation)
            Map<COSDictionary, Set<Integer>> recoveredCodes;
            try {
                recoveredCodes = unicodeRecovery.recover(document, pagesToRead);
            } catch (IllegalStateException e) {
                // Le recovery a échoué : PDF malformé, on continue sans
                log.warn("Recovery des polices échoué pour {}, extraction sans recovery : {}", filePath, e.getMessage());
                recoveredCodes = Collections.emptyMap();
            }

            GlyphCountingStripper stripper = new GlyphCountingStripper(recoveredCodes);

            for (int i = 0; i < pagesToRead; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(document);

                pages.add(new PDFPage(i + 1, text));
            }

            return new PDFExtraction(pages, pageCount, stripper.glyphs, stripper.lostGlyphs, stripper.recoveredGlyphs);
        }
    }

    /**
     * Extracteur qui compte, au passage, les glyphes sans caractère Unicode et ceux reconstitués.
     */
    private static class GlyphCountingStripper extends PDFTextStripper {
        private final Map<COSDictionary, Set<Integer>> recoveredCodes;
        private int glyphs;
        private int lostGlyphs;
        private int recoveredGlyphs;

        GlyphCountingStripper(Map<COSDictionary, Set<Integer>> recoveredCodes) {
            this.recoveredCodes = recoveredCodes;
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
            glyphs++;
            if (font.toUnicode(code) == null) {
                lostGlyphs++;
            } else if (recoveredCodes.getOrDefault(font.getCOSObject(), Set.of()).contains(code)) {
                recoveredGlyphs++;
            }
            super.showGlyph(textRenderingMatrix, font, code, displacement);
        }
    }

    /**
     * Page PDF avec son numéro et son contenu textuel
     *
     * @param pageNumber Numéro de la page (à partir de 1)
     * @param text       Texte extrait de la page
     */
    public record PDFPage(int pageNumber, String text) {
    }
}
