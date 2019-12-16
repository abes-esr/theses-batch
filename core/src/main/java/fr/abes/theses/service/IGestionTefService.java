package fr.abes.theses.service;

import fr.abes.theses.model.dto.NoticeBiblioDto;
import org.dom4j.DocumentException;

public interface IGestionTefService {

    void majTraitementSortieSudoc(NoticeBiblioDto noticeBiblioDto, Integer idDoc, String codeEtab) throws InstantiationException, DocumentException;

    void majDonneesGestion(NoticeBiblioDto dto, Integer idDoc, String codeEtab) throws InstantiationException, DocumentException;
}
