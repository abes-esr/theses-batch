package fr.abes.theses.tasklets.traiternoticebibliochunk;

import fr.abes.theses.model.entities.Document;
import fr.abes.theses.model.entities.NoticeBiblio;
import fr.abes.theses.service.ServiceProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NoticeBiblioProcessor implements ItemProcessor<NoticeBiblio, NoticeBiblio>, StepExecutionListener {

   /* @Autowired
    StrategyFactory factory;

    @Autowired
    ProxyRetry proxyRetry;*/
    @Autowired
    @Getter
   ServiceProvider service;

    private NoticeBiblio noticeBiblio;


    @Override
    public void beforeStep(StepExecution stepExecution) {
       /* ExecutionContext executionContext = stepExecution
                .getJobExecution()
                .getExecutionContext();
        this.noticeBiblio = (NoticeBiblio) executionContext.get("noticeBiblio");
        //log.info("...pour la noticeBiblio " + this.noticeBiblio.getId());*/
    }

    /**
     * This method allows the conversion from marcxml Star tef format (Star.Document) to marc sudoc database format
     *
     * @param
     * @return
     * @throws
     */

    @Override
    public NoticeBiblio process(NoticeBiblio noticeBiblio) throws Exception {

      /*  try {
            int iddoc = ligneFichierDto.getIddoc();

        } catch (CBSException e) {
            log.error("erreur lors de la requête au Sudoc ou du saveExemplaire" + e.toString());
            ligneFichierDto.setRetourSudoc(e.getMessage());
        } catch (Exception e) {
            log.error("erreur lors de la recup de la noticetraitee : " + e.toString());
            ligneFichierDto.setRetourSudoc(e.getMessage());
        }*/

        Document doc = getService().getDocumentService().findById(noticeBiblio.getId());

        log.info("dans le process = " + noticeBiblio + " Doc:" + doc.getDoc());
        return noticeBiblio;
    }


    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return null;
    }
}



