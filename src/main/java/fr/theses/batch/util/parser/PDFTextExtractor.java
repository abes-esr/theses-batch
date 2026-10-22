package fr.theses.batch.util.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

    /** Valeur par défaut de app.anr.in-memory-max-size */
    static final DataSize DEFAULT_IN_MEMORY_MAX_SIZE = DataSize.ofMegabytes(64);

    /**
     * Taille des lectures d'un PDF chargé en mémoire. Le JDK fait passer chaque lecture par un
     * tampon direct de cette taille qu'il garde ensuite pour le thread : lire le fichier d'un seul
     * appel en garderait un de la taille du plus gros PDF lu, par thread.
     */
    static final int READ_BLOCK_SIZE = 1 << 20;

    private final SystemFontUnicodeRecovery unicodeRecovery;
    private final long inMemoryMaxBytes;

    public PDFTextExtractor(SystemFontUnicodeRecovery unicodeRecovery) {
        this(unicodeRecovery, DEFAULT_IN_MEMORY_MAX_SIZE);
    }

    /**
     * @param inMemoryMaxSize Taille maximale d'un PDF lu en entier en mémoire avant l'analyse ;
     *                        au-delà (ou avec 0), il est lu par pages de 4 ko au fil de l'analyse
     */
    @Autowired
    public PDFTextExtractor(SystemFontUnicodeRecovery unicodeRecovery,
                            @Value("${app.anr.in-memory-max-size:64MB}") DataSize inMemoryMaxSize) {
        this.unicodeRecovery = unicodeRecovery;
        // Un tableau Java ne dépasse pas 2 Go
        this.inMemoryMaxBytes = Math.min(inMemoryMaxSize.toBytes(), Integer.MAX_VALUE - 8);
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

        if (filePath == null) {
            throw new IOException("File not found: null");
        }

        RandomAccessRead input = open(filePath);
        long fileSize = input.length();
        try (PDDocument document = Loader.loadPDF(input)) {
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

            return new PDFExtraction(pages, pageCount, stripper.glyphs, stripper.lostGlyphs, stripper.recoveredGlyphs,
                    fileSize);
        }
    }

    /**
     * Ouvre le PDF pour PDFBox. Jusqu'à app.anr.in-memory-max-size, il est lu en entier, du début
     * à la fin, par blocs de {@link #READ_BLOCK_SIZE} : sur NFS, le client envoie alors des requêtes
     * de taille maximale (rsize) et lit en avance, alors que PDFBox, qui saute d'un objet à l'autre
     * par pages de 4 ko, coûte un aller-retour avec le serveur presque à chaque page. Au-delà, le
     * fichier est lu par pages au fil de l'analyse, sans être chargé en mémoire.
     * <p>
     * Pas de File.exists() préalable : l'ouverture signale déjà un fichier absent, et sur NFS
     * chaque appel système de plus coûte un aller-retour avec le serveur. La taille est celle du
     * fichier déjà ouvert.
     */
    RandomAccessRead open(String filePath) throws IOException {
        try {
            try (FileChannel channel = FileChannel.open(Path.of(filePath))) {
                long size = channel.size();
                if (size <= inMemoryMaxBytes) {
                    return new RandomAccessReadBuffer(readFully(channel, (int) size, filePath));
                }
            }
            return new RandomAccessReadBufferedFile(new File(filePath));
        } catch (NoSuchFileException | FileNotFoundException e) {
            throw new IOException("File not found: " + filePath, e);
        }
    }

    private static byte[] readFully(FileChannel channel, int size, String filePath) throws IOException {
        byte[] content = new byte[size];
        int position = 0;
        while (position < size) {
            int read = channel.read(ByteBuffer.wrap(content, position, Math.min(READ_BLOCK_SIZE, size - position)));
            if (read < 0) {
                throw new IOException("Fichier raccourci pendant sa lecture (" + position + " octets sur " + size
                        + ") : " + filePath);
            }
            position += read;
        }
        return content;
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
