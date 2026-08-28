package fr.theses.batch.job.processor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service pour le traitement des fichiers PDF
 * Utilise Apache PDFBox pour extraire le texte page par page
 */
@Service
public class PDFProcessingService {
    
    /**
     * Extrait le texte d'un fichier PDF avec un maximum de pages
     * 
     * @param filePath Chemin du fichier PDF
     * @param maxPages Nombre maximum de pages à lire (0 pour toutes)
     * @return Liste de pages avec leur numéro et contenu textuel
     * @throws IOException En cas d'erreur de lecture
     */
    public List<PDFPage> extractTextFromPdf(String filePath, int maxPages) throws IOException {
        List<PDFPage> pages = new ArrayList<>();
        
        if (filePath == null || !new File(filePath).exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(new File(filePath)))) {
            int pageCount = document.getNumberOfPages();
            int pagesToRead = maxPages > 0 ? Math.min(maxPages, pageCount) : pageCount;
            
            PDFTextStripper stripper = new PDFTextStripper();
            
            for (int i = 0; i < pagesToRead; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(document);
                
                pages.add(new PDFPage(i + 1, text));
            }
        }
        
        return pages;
    }
    
    /**
     * Obtient le nombre de pages dans un PDF
     * 
     * @param filePath Chemin du fichier PDF
     * @return Nombre de pages
     * @throws IOException En cas d'erreur de lecture
     */
    public int getPageCount(String filePath) throws IOException {
        if (filePath == null || !new File(filePath).exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(new File(filePath)))) {
            return document.getNumberOfPages();
        }
    }
    
    /**
     * Classe interne pour représenter une page PDF avec son numéro et son contenu
     */
    public static class PDFPage {
        private final int pageNumber;
        private final String text;
        
        public PDFPage(int pageNumber, String text) {
            this.pageNumber = pageNumber;
            this.text = text;
        }
        
        public int getPageNumber() {
            return pageNumber;
        }
        
        public String getText() {
            return text;
        }
    }
}
