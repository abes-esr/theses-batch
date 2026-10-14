package fr.theses.batch.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Génère des PDF de test : une page par texte fourni, écrit en Helvetica (police standard, sans fichier à embarquer)
 */
public final class TestPdfs {

    /**
     * Motif ANR de référence, identique à celui de la configuration locale
     */
    public static final String ANR_PATTERN =
            "\\b(?:ANR-(?:\\d{2}-)?[A-Za-z0-9]{4,8}(?:-\\d{1,4})?|Agence nationale de la recherche|Labex|PIA)\\b";

    private TestPdfs() {
    }

    /**
     * Crée un PDF dont chaque page porte une ligne de texte ; une chaîne vide donne une page blanche
     *
     * @param file  fichier à créer (répertoires parents créés au besoin)
     * @param pages texte de chaque page
     * @return le fichier créé
     */
    public static Path create(Path file, String... pages) throws IOException {
        Files.createDirectories(file.getParent());
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }
}
