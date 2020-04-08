package fr.abes.theses.model;

import fr.abes.theses.service.XPathService;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.*;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Date;

import static fr.abes.theses.service.XPathService.*;

@Slf4j
public class Tef {

    private static final String ERROR_MSG_EMPTY_TEF = "Erreur : le champ documentTef dans MajDonneesGestionTef est null.";

    public Document documentTef = null;


    public Tef(String document) throws DocumentException, InstantiationException {
        documentTef = DocumentHelper.parseText(document);
        checkDocumenTef();
    }

    public Tef(Document document) throws InstantiationException {
        this.documentTef = document;
        checkDocumenTef();
    }


    public void setStarGestionAttribut(Date dateCreation, Date dateModification, String retourSudoc, String indicSudoc, String ppn) throws InstantiationException {
        checkDocumenTef();
        SimpleDateFormat formater = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        try{
            setDate(formater.format(dateModification));
        } catch (Exception e){
            log.info(e.getMessage());
        }

        if (retourSudoc.contains("KO")) {
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "trace", retourSudoc, documentTef);
        } else {
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "PPN", ppn, documentTef);
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "trace", "", documentTef);
        }
    }

    public void setStarGestionAttribut(String date, Document retour) throws InstantiationException {
        checkDocumenTef();

        setDate(date);

        String codeRetour = setCodeRetour(retour);

        if (codeRetour.contains("KO")) {
            setMessage(retour, "THESE/BIBLIO/MESSAGE", "trace");
        } else {
            setMessage(retour, "THESE/BIBLIO/PPN", "PPN");
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "trace", "", documentTef);
        }

    }

    public void setStarGestionAttributExemplaire(Date updateDate, String indicSudoc, String trace) throws InstantiationException {
        checkDocumenTef();

        SimpleDateFormat formater = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        setDate(formater.format(updateDate));
        setIndicSudoc(indicSudoc);
        setTrace(trace);

    }

    private void setIndicSudoc(String indicSudoc) {
        XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "indicSudoc", indicSudoc, documentTef);
    }

    private void setTrace(String indicSudoc) {
        XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "trace", indicSudoc, documentTef);
    }

    private void setMessage(Document retour, String path, String trace) {
        String message = XPathService.getValue(path, retour);
        XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, trace, message, documentTef);
    }

    private String setCodeRetour(Document retour) {
        String codeRetour = XPathService.getValue("THESE/BIBLIO/CODERETOUR", retour);
        setIndicSudoc(codeRetour);
        return codeRetour;
    }

    private void setDate(String date) {
        String dateSudoc = XPathService.getAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "dateSudoc", documentTef);
        if (dateSudoc != null && !dateSudoc.isEmpty()) {
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "majSudoc", date, documentTef);
        } else {
            XPathService.setAttribut(XPATH_STAR_GEST_TRTS_SORTIES_SUDOC, "dateSudoc", date, documentTef);
        }
    }


    private void checkDocumenTef() throws InstantiationException {
        if (documentTef == null) {
            log.error(ERROR_MSG_EMPTY_TEF);
            throw new InstantiationException("DocumentTef n'est pas initalisé");
        }
    }

}
